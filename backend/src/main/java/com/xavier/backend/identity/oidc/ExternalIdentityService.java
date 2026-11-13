package com.xavier.backend.identity.oidc;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.AssuranceState;
import com.xavier.backend.access.AuthenticationAssurance;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StepUpRequiredException;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.UserNotFoundException;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.MfaState;
import com.xavier.backend.identity.oidc.exception.ExternalIdentityExistsException;
import com.xavier.backend.identity.oidc.exception.ExternalIdentityNotFoundException;
import com.xavier.backend.identity.oidc.exception.SupportAccountCannotLinkException;
import com.xavier.backend.identity.oidc.model.ExternalIdentity;
import com.xavier.backend.identity.oidc.model.dto.ExternalIdentityView;
import com.xavier.backend.identity.oidc.repository.ExternalIdentityRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import com.xavier.backend.identity.service.MfaSessionAttributes;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The links between BackOffice accounts and the installation's OpenID Provider (D144): the owner starts a link from
 * a real session (never a password-only one: {@code /api/v1/auth/**} is open to {@code MFA_PENDING} sessions, and a
 * pending session must not attach an IdP identity that then logs in without the second factor), the callback
 * completes it, and the owner or an administrator ({@code USER_MANAGE}) removes it. Never a token here.
 */
@Service
@ConditionalOnOidcEnabled
public class ExternalIdentityService {

    /** Session attribute: the account ({@code UUID}) whose session asked to link the next IdP identity. */
    static final String PENDING_LINK = "identity.oidc.pendingLink";
    /** Session attribute: when ({@code Instant}); a link not completed within {@link #LINK_VALIDITY} is dropped. */
    static final String PENDING_LINK_AT = "identity.oidc.pendingLinkAt";
    static final Duration LINK_VALIDITY = Duration.ofMinutes(10);

    private final AccessPolicy accessPolicy;
    private final AuthenticationAssurance assurance;
    private final UserAccountRepository accounts;
    private final ExternalIdentityRepository identities;
    private final AuditLog auditLog;
    private final Clock clock;

    ExternalIdentityService(AccessPolicy accessPolicy, AuthenticationAssurance assurance,
                            UserAccountRepository accounts, ExternalIdentityRepository identities,
                            AuditLog auditLog, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.assurance = assurance;
        this.accounts = accounts;
        this.identities = identities;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * Marks this session as linking: the next successful callback links the IdP identity to its account.
     *
     * @throws StepUpRequiredException            403 {@code MFA_STEP_UP_REQUIRED} / {@code MFA_ENROLMENT_REQUIRED}:
     *                                            a pending login, or no fresh second factor while MFA is not OFF
     * @throws SupportAccountCannotLinkException  422
     * @throws ExternalIdentityExistsException    409 the account already has a link
     */
    @Transactional(readOnly = true)
    public void startLink(HttpSession session) {
        UserAccount account = ownAccount(session);
        if (account.isSupport()) {
            throw new SupportAccountCannotLinkException();
        }
        requireFreshSecondFactor();
        if (identities.existsByAccountId(account.getId())) {
            throw new ExternalIdentityExistsException();
        }
        session.setAttribute(PENDING_LINK, account.getId());
        session.setAttribute(PENDING_LINK_AT, clock.instant());
    }

    /**
     * The account a callback should link to, if this session asked for it in the last 10 minutes; the request is
     * consumed either way.
     */
    Optional<UUID> takePendingLink(HttpSession session) {
        if (session == null || !(session.getAttribute(PENDING_LINK) instanceof UUID accountId)) {
            return Optional.empty();
        }
        Instant since = session.getAttribute(PENDING_LINK_AT) instanceof Instant at ? at : Instant.MIN;
        session.removeAttribute(PENDING_LINK);
        session.removeAttribute(PENDING_LINK_AT);
        return since.plus(LINK_VALIDITY).isAfter(clock.instant()) ? Optional.of(accountId) : Optional.empty();
    }

    /**
     * Links {@code (issuer, subject)} to {@code linker}'s own account; audited {@code OIDC_LINKED}. The database
     * decides duplicates, also in a race: {@code UNIQUE (account_id)} and {@code UNIQUE (issuer, subject)}.
     *
     * @throws ExternalIdentityExistsException the account has a link, or the identity is linked to another account
     */
    @Transactional
    public ExternalIdentityView link(Actor linker, String issuer, String subject) {
        ExternalIdentity saved = UniqueConstraints.saveAndFlush(identities,
                ExternalIdentity.link(linker.userId(), issuer, subject, clock.instant()),
                ExternalIdentityExistsException::new,
                "external_identity_issuer_subject_uk", "external_identity_account_uk");
        auditLog.recordAs(linker, "OIDC_LINKED", "UserAccount", linker.userId().toString(), null, null,
                Map.of("issuer", issuer));
        return saved.toView();
    }

    /** The account linked to {@code (issuer, subject)}, if any. */
    @Transactional(readOnly = true)
    public Optional<UserAccount> linkedAccount(String issuer, String subject) {
        return identities.findByIssuerAndSubject(issuer, subject).flatMap(link -> accounts.findById(link.getAccountId()));
    }

    /** A federated login started a session: {@code last_login_at}, and audited {@code OIDC_LOGIN}. */
    @Transactional
    public void loggedIn(Actor session, String issuer, String subject, MfaState mfa) {
        identities.findByIssuerAndSubject(issuer, subject).ifPresent(link -> link.loggedIn(clock.instant()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("issuer", issuer);
        details.put("mfa", mfa.name());
        auditLog.recordAs(session, "OIDC_LOGIN", "UserAccount", session.userId().toString(), null, null, details);
    }

    /**
     * Audited {@code OIDC_LOGIN_REFUSED}, with the issuer and why, never the subject or a token.
     *
     * @param accountId the linked account, or {@code null} when the identity is linked to none
     */
    @Transactional
    public void refused(String issuer, UUID accountId, String reason) {
        auditLog.recordAsSystem("OIDC_LOGIN_REFUSED", accountId == null ? "OidcProvider" : "UserAccount",
                accountId == null ? LazyClientRegistrationRepository.REGISTRATION_ID : accountId.toString(), null,
                null, null, Map.of("issuer", issuer, "reason", reason));
    }

    /** The caller's own link (a real session, not a pending one). */
    @Transactional(readOnly = true)
    public ExternalIdentityView ownLink(HttpSession session) {
        UUID accountId = ownAccount(session).getId();
        return identities.findByAccountId(accountId).map(ExternalIdentity::toView)
                .orElseThrow(() -> new ExternalIdentityNotFoundException(accountId));
    }

    /** Removes the caller's own link; audited {@code OIDC_UNLINKED}. Password login stays (P56). */
    @Transactional
    public void unlinkOwn(HttpSession session) {
        unlink(ownAccount(session).getId());
    }

    @Transactional(readOnly = true)
    public ExternalIdentityView linkOf(UUID accountId) {
        accessPolicy.require(Permission.USER_MANAGE);
        return identities.findByAccountId(accountId).map(ExternalIdentity::toView)
                .orElseThrow(() -> new ExternalIdentityNotFoundException(accountId));
    }

    /** An administrator removes an account's link; audited {@code OIDC_UNLINKED}. */
    @Transactional
    public void unlinkAccount(UUID accountId) {
        accessPolicy.require(Permission.USER_MANAGE);
        unlink(accountId);
    }

    private void unlink(UUID accountId) {
        if (identities.deleteByAccountId(accountId) == 0) {
            throw new ExternalIdentityNotFoundException(accountId);
        }
        auditLog.recordAsCurrentActor("OIDC_UNLINKED", "UserAccount", accountId.toString(), null, null, null,
                Map.of());
    }

    /**
     * The caller's account, from a session that finished logging in: not waiting for a second factor or an enrolment
     * (D141), and not waiting for a password change (D30).
     */
    private UserAccount ownAccount(HttpSession session) {
        Actor actor = accessPolicy.current();
        if (actor.kind() != ActorKind.ACCOUNT) {
            throw new AccessDeniedException("Only a BackOffice account can be linked to an identity provider.");
        }
        MfaState state = MfaSessionAttributes.state(session);
        if (state == MfaState.PENDING) {
            throw StepUpRequiredException.stepUpRequired();
        }
        if (state == MfaState.ENROLMENT_REQUIRED) {
            throw StepUpRequiredException.enrolmentRequired();
        }
        UserAccount account = accounts.findById(actor.userId())
                .orElseThrow(() -> new UserNotFoundException(actor.userId()));
        if (account.isMustChangePassword()) {
            throw new AccessDeniedException("Change your password first.");
        }
        return account;
    }

    /** D144: "with a fresh second factor when MFA is not OFF", the same test as a sensitive permission (D142). */
    private void requireFreshSecondFactor() {
        AssuranceState state = assurance.current();
        if (!state.enforced()) {
            return;
        }
        if (!state.enrolled()) {
            throw StepUpRequiredException.enrolmentRequired();
        }
        Instant at = state.secondFactorAt();
        if (at == null || at.plus(state.maxAge()).isBefore(clock.instant())) {
            throw StepUpRequiredException.stepUpRequired();
        }
    }
}
