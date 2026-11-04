package com.xavier.backend.identity.controller;

import com.xavier.backend.access.Actor;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.service.LoginAudit;
import com.xavier.backend.identity.service.SessionPrincipals;
import com.xavier.backend.identity.service.TerminalSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
 *       store ends it.</li>
 * </ol>
 */
@Component
class LoginSessions {

    /** Session attribute: set while the account must change its password (see {@code MustChangePasswordFilter}). */
    static final String MUST_CHANGE_PASSWORD = "identity.mustChangePassword";

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

    LoginSessions(TerminalSessionService terminalSessions, LoginAudit audit) {
        this.terminalSessions = terminalSessions;
        this.audit = audit;
    }

    /** @return the id of the session that now holds the actor */
    String start(SessionView view, HttpServletRequest request, HttpServletResponse response) {
        Actor actor = view.toActor();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.authorities().stream().map(SimpleGrantedAuthority::new).toList());
        loginStrategy.onAuthentication(authentication, request, response);
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrf) {
            csrf.getToken(); // generates and saves the new token now, so this response sets the cookie
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, SessionPrincipals.of(actor));
        if (view.mustChangePassword()) {
            session.setAttribute(MUST_CHANGE_PASSWORD, Boolean.TRUE);
        } else {
            session.removeAttribute(MUST_CHANGE_PASSWORD);
        }
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contexts.saveContext(context, request, response);
        if (actor.terminalId() != null) {
            terminalSessions.register(session.getId(), actor.terminalId(), actor.storeId(), actor.employeeId());
        }
        return session.getId();
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
