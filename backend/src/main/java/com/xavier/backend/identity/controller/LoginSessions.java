package com.xavier.backend.identity.controller;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.identity.model.dto.MfaState;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.service.LoginAudit;
import com.xavier.backend.identity.service.MfaSessionAttributes;
import com.xavier.backend.identity.service.SessionPrincipals;
import com.xavier.backend.identity.service.SupportGrantService;
import com.xavier.backend.identity.service.TerminalSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Puts a logged-in {@link Actor} into the HTTP session (stored by Spring Session JDBC), the way Spring
 * Security's own login filters do; our JSON login endpoints are plain controllers, so this is done explicitly:
 *
 * <ol>
 *   <li>the session id changes (session fixation protection) or a new session starts, and the CSRF token is
 *       replaced; the login response already carries the new {@code XSRF-TOKEN} cookie;</li>
 *   <li>the principal name index is set to the actor kind and id ({@link SessionPrincipals}, D38) so sessions can
 *       be listed and revoked per account or employee ({@link FindByIndexNameSessionRepository}); without it Spring
 *       Session would index {@code Actor.toString()};</li>
 *   <li>the {@code Authentication}'s granted authorities are exactly {@code Actor.authorities()} (the actuator URL
 *       rule reads them, {@code AccessPolicy} reads the actor);</li>
 *   <li>the security context is saved in the session explicitly ({@link HttpSessionSecurityContextRepository}),
 *       which the security filter chain loads on every later request;</li>
 *   <li>a POS session is recorded against its terminal and store, so revoking the terminal or deactivating the
 *       store ends it;</li>
 *   <li>a support account's session records its grant and that grant's end, which {@code SupportSessionFilter}
 *       checks on every request, and may stay idle at most for the rest of the grant (D83);</li>
 *   <li>a BackOffice session records its second-factor state, when it proved the factor, and when a pending login
 *       started ({@link MfaSessionAttributes}, D141), which {@code MustChangeCredentialFilter},
 *       {@code MfaRemoteFilter} and the step-up check read.</li>
 * </ol>
 */
@Component
public class LoginSessions {

    /** Session attribute: set while the account must change its password (see {@code MustChangeCredentialFilter}). */
    static final String MUST_CHANGE_PASSWORD = "identity.mustChangePassword";
    /** Session attribute: set while the employee must replace a manager-set PIN (see {@code MustChangeCredentialFilter}). */
    static final String MUST_CHANGE_PIN = "identity.mustChangePin";
    /** Session attributes of a support session (D83): its grant ({@code UUID}, absent when none is usable). */
    static final String SUPPORT_GRANT_ID = "identity.supportGrantId";
    /** The grant's end ({@code Instant}); its presence marks a support session (see {@code SupportSessionFilter}). */
    static final String SUPPORT_VALID_UNTIL = "identity.supportValidUntil";

    /**
     * The same cookie repository {@code csrf.spa()} configures in {@code SecurityConfig}; it keeps no state, so this
     * instance reads and writes the very same {@code XSRF-TOKEN} cookie.
     */
    private final SessionAuthenticationStrategy loginStrategy = new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(),
            new CsrfAuthenticationStrategy(CookieCsrfTokenRepository.withHttpOnlyFalse())));
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final TerminalSessionService terminalSessions;
    private final LoginAudit audit;
    private final SupportGrantService supportGrants;
    private final Clock clock;

    LoginSessions(TerminalSessionService terminalSessions, LoginAudit audit, SupportGrantService supportGrants,
                  Clock clock) {
        this.terminalSessions = terminalSessions;
        this.audit = audit;
        this.supportGrants = supportGrants;
        this.clock = clock;
    }

    /** A session that has not proved a second factor (or a POS session). @return the new session's id */
    String start(SessionView view, HttpServletRequest request, HttpServletResponse response) {
        return start(view, null, request, response);
    }

    /**
     * @param secondFactorAt when the session proved its second factor ({@code null}: not yet)
     * @return the id of the session that now holds the actor
     */
    public String start(SessionView view, Instant secondFactorAt, HttpServletRequest request,
                        HttpServletResponse response) {
        Actor actor = view.toActor();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.authorities().stream().map(SimpleGrantedAuthority::new).toList());
        String previousSessionId = request.getSession(false) == null ? null : request.getSession(false).getId();
        loginStrategy.onAuthentication(authentication, request, response);
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrf) {
            csrf.getToken(); // generates and saves the new token now, so this response sets the cookie
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, SessionPrincipals.of(actor));
        flag(session, MUST_CHANGE_PASSWORD, view.mustChangePassword());
        flag(session, MUST_CHANGE_PIN, view.mustChangePin());
        recordSecondFactor(session, view.mfa().state(), secondFactorAt);
        session.removeAttribute(SUPPORT_GRANT_ID);
        session.removeAttribute(SUPPORT_VALID_UNTIL);
        if (actor.kind() == ActorKind.ACCOUNT) {
            supportGrants.sessionGrantOf(actor.userId()).ifPresent(grant -> {
                session.setAttribute(SUPPORT_GRANT_ID, grant.grantId());
                session.setAttribute(SUPPORT_VALID_UNTIL, grant.validUntil());
                capIdleTime(session, grant.remaining());
            });
        }
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contexts.saveContext(context, request, response);
        if (actor.terminalId() != null) {
            if (previousSessionId != null) {
                terminalSessions.forget(previousSessionId); // the id changed: the old one is gone
            }
            terminalSessions.register(session.getId(), actor.terminalId(), actor.storeId(), actor.employeeId());
        }
        return session.getId();
    }

    private void recordSecondFactor(HttpSession session, MfaState state, Instant secondFactorAt) {
        set(session, MfaSessionAttributes.MFA_STATE, state == MfaState.NONE ? null : state.name());
        set(session, MfaSessionAttributes.MFA_SECOND_FACTOR_AT, secondFactorAt);
        set(session, MfaSessionAttributes.MFA_PENDING_SINCE, state == MfaState.PENDING ? clock.instant() : null);
    }

    /** Step-up (D142): the session proved its second factor again, now. */
    void steppedUp(HttpServletRequest request, Instant secondFactorAt) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(MfaSessionAttributes.MFA_SECOND_FACTOR_AT, secondFactorAt);
        }
    }

    private static void set(HttpSession session, String attribute, Object value) {
        if (value == null) {
            session.removeAttribute(attribute);
        } else {
            session.setAttribute(attribute, value);
        }
    }

    /** At least one second: zero would mean "never times out" to the servlet API. */
    private static void capIdleTime(HttpSession session, Duration limit) {
        long seconds = Math.max(1, Math.min(session.getMaxInactiveInterval(), limit.toSeconds()));
        session.setMaxInactiveInterval((int) seconds);
    }

    private static void flag(HttpSession session, String attribute, boolean set) {
        if (set) {
            session.setAttribute(attribute, Boolean.TRUE);
        } else {
            session.removeAttribute(attribute);
        }
    }

    void end(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            terminalSessions.forget(session.getId());
        }
        Authentication authentication = holder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Actor actor) {
            audit.loggedOut(actor);
        }
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
}
