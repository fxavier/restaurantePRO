package com.xavier.backend.identity.controller;

import com.xavier.backend.access.Actor;
import com.xavier.backend.identity.model.dto.SessionView;
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
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Puts a logged-in {@link Actor} into the HTTP session (stored by Spring Session JDBC), the way Spring
 * Security's own login filters do; our JSON login endpoints are plain controllers, so this is done explicitly:
 *
 * <ol>
 *   <li>the session id changes (session fixation protection) or a new session starts;</li>
 *   <li>the principal name index is set to {@code Actor.userId()} so sessions can be listed and revoked per user
 *       ({@link FindByIndexNameSessionRepository}); without it Spring Session would index {@code Actor.toString()};</li>
 *   <li>the {@code Authentication}'s granted authorities are exactly {@code Actor.authorities()} (the actuator URL
 *       rule reads them, {@code AccessPolicy} reads the actor);</li>
 *   <li>the security context is saved in the session explicitly ({@link HttpSessionSecurityContextRepository}),
 *       which the security filter chain loads on every later request.</li>
 * </ol>
 */
@Component
class LoginSessions {

    /** Session attribute: set while the account must change its password (see {@code MustChangePasswordFilter}). */
    static final String MUST_CHANGE_PASSWORD = "identity.mustChangePassword";

    private final SessionAuthenticationStrategy fixationProtection =
            new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy()));
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    /** @return the id of the session that now holds the actor */
    String start(SessionView view, HttpServletRequest request, HttpServletResponse response) {
        Actor actor = view.toActor();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.authorities().stream().map(SimpleGrantedAuthority::new).toList());
        fixationProtection.onAuthentication(authentication, request, response);
        HttpSession session = request.getSession(true);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, actor.userId().toString());
        if (view.mustChangePassword()) {
            session.setAttribute(MUST_CHANGE_PASSWORD, Boolean.TRUE);
        } else {
            session.removeAttribute(MUST_CHANGE_PASSWORD);
        }
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contexts.saveContext(context, request, response);
        return session.getId();
    }

    void end(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, holder.getContext().getAuthentication());
    }
}
