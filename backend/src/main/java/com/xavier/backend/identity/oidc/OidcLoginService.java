package com.xavier.backend.identity.oidc;

import com.xavier.backend.access.Actor;
import com.xavier.backend.identity.controller.LoginSessions;
import com.xavier.backend.identity.exception.MfaRefusedException;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.MfaSessionView;
import com.xavier.backend.identity.model.dto.MfaState;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.oidc.exception.ExternalIdentityExistsException;
import com.xavier.backend.identity.oidc.exception.SupportAccountCannotLinkException;
import com.xavier.backend.identity.service.LoginService;
import com.xavier.backend.identity.service.MfaPolicy;
import com.xavier.backend.identity.service.RemoteAddresses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * What a validated ID token does (D144), once Spring Security has checked {@code state}, {@code nonce}, PKCE and the
 * token's signature, issuer, audience and expiry:
 *
 * <ul>
 *   <li>the session asked to link ({@code POST /auth/oidc/link}): the {@code (iss, sub)} is linked to that session's
 *       own account, and the session is otherwise unchanged;</li>
 *   <li>otherwise a login: the linked account gets the same server-side session as a password login (D25) with its
 *       local authorities only (no claim is read but {@code iss}, {@code sub} and {@code amr}), through the same
 *       second-factor rule (D140, D141); the IdP's {@code amr} counts as the second factor only when the operator set
 *       {@code trust-idp-mfa} (P55). No account is ever created; an unlinked identity, an inactive account and a
 *       support account are refused like a wrong password and audited {@code OIDC_LOGIN_REFUSED}.</li>
 * </ul>
 *
 * The tokens are discarded: nothing but the session's own attributes is kept.
 */
@Service
@ConditionalOnOidcEnabled
public class OidcLoginService {

    /** {@code amr} values (RFC 8176) that show the IdP asked for more than one factor. */
    static final Set<String> MFA_METHODS = Set.of("mfa", "otp", "hwk", "swk");

    private static final Logger log = LoggerFactory.getLogger(OidcLoginService.class);

    private final ExternalIdentityService identities;
    private final LoginService logins;
    private final LoginSessions sessions;
    private final MfaPolicy mfaPolicy;
    private final RemoteAddresses addresses;
    private final OidcProperties properties;
    private final Clock clock;

    OidcLoginService(ExternalIdentityService identities, LoginService logins, LoginSessions sessions,
                     MfaPolicy mfaPolicy, RemoteAddresses addresses, OidcProperties properties, Clock clock) {
        this.identities = identities;
        this.logins = logins;
        this.sessions = sessions;
        this.mfaPolicy = mfaPolicy;
        this.addresses = addresses;
        this.properties = properties;
        this.clock = clock;
    }

    /** Links or logs in, then redirects the browser: to {@code post-login-uri}, or to the login page's error. */
    public void complete(String issuer, String subject, Set<String> amr, HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        Optional<UUID> linking = identities.takePendingLink(session);
        if (linking.isPresent()) {
            completeLink(linking.get(), session, issuer, subject, response);
            return;
        }
        UserAccount account = identities.linkedAccount(issuer, subject).orElse(null);
        String refusal = refusalOf(account);
        if (refusal != null) {
            refuse(issuer, account, refusal, response);
            return;
        }
        boolean idpProvedMfa = properties.trustIdpMfa() && amr.stream().anyMatch(MFA_METHODS::contains);
        SessionView view;
        Instant secondFactorAt = null;
        if (mfaPolicy.enforced() && idpProvedMfa) {
            secondFactorAt = clock.instant();
            view = logins.sessionOf(account)
                    .withMfa(MfaSessionView.of(MfaState.VERIFIED, secondFactorAt, mfaPolicy.stepUpMaxAge()));
        } else {
            try {
                view = logins.federatedSession(account, addresses.isRemote(request));
            } catch (MfaRefusedException e) {
                refuse(issuer, account, e.code(), response);
                return;
            }
        }
        sessions.start(view, secondFactorAt, request, response);
        identities.loggedIn(view.toActor(), issuer, subject, view.mfa().state());
        log.info("Federated login of user {} from {} (second factor: {})", account.getId(), issuer,
                view.mfa().state());
        response.sendRedirect(properties.postLoginUri());
    }

    private void completeLink(UUID accountId, HttpSession session, String issuer, String subject,
                              HttpServletResponse response) throws IOException {
        Actor linker = sessionActor(session).filter(actor -> actor.userId().equals(accountId)).orElse(null);
        if (linker == null) {
            log.warn("OIDC link of user {} dropped: the session no longer holds that account", accountId);
            response.sendRedirect(linkError());
            return;
        }
        try {
            identities.link(linker, session, issuer, subject);
        } catch (ExternalIdentityExistsException | SupportAccountCannotLinkException e) {
            log.warn("OIDC link of user {} to {} refused: {}", accountId, issuer, e.code());
            response.sendRedirect(linkError());
            return;
        } catch (AccessDeniedException e) {
            // D144 amended: the session is no longer the finished, freshly proved login that asked
            log.warn("OIDC link of user {} to {} refused: {}", accountId, issuer, e.getMessage());
            response.sendRedirect(linkError());
            return;
        }
        log.info("User {} linked to an identity at {}", accountId, issuer);
        response.sendRedirect(properties.postLoginUri());
    }

    private static String refusalOf(UserAccount account) {
        if (account == null) {
            return "UNLINKED";
        }
        if (!account.isActive()) {
            return "INACTIVE";
        }
        return account.isSupport() ? "SUPPORT_ACCOUNT" : null;
    }

    private void refuse(String issuer, UserAccount account, String reason, HttpServletResponse response)
            throws IOException {
        UUID accountId = account == null ? null : account.getId();
        log.warn("Federated login from {} refused: {} (account {})", issuer, reason, accountId);
        identities.refused(issuer, accountId, reason);
        response.sendRedirect(OidcRoutes.LOGIN_ERROR);
    }

    /**
     * The account this session was logged in as. Read from the stored context: during the callback the current one
     * holds Spring's OAuth2 token, which never reaches the session.
     */
    private static Optional<Actor> sessionActor(HttpSession session) {
        return session != null
                && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                        instanceof SecurityContext context
                && context.getAuthentication() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof Actor actor
                ? Optional.of(actor) : Optional.empty();
    }

    private String linkError() {
        return UriComponentsBuilder.fromUriString(properties.postLoginUri()).queryParam("error", "oidc-link")
                .build().toUriString();
    }
}
