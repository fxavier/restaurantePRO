package com.xavier.backend.identity.oidc;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StepUpRequiredException;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.UserNotFoundException;
import com.xavier.backend.identity.model.MfaFactorStatus;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.MfaState;
import com.xavier.backend.identity.oidc.exception.ExternalIdentityExistsException;
import com.xavier.backend.identity.oidc.exception.ExternalIdentityNotFoundException;
import com.xavier.backend.identity.oidc.exception.SupportAccountCannotLinkException;
import com.xavier.backend.identity.oidc.model.ExternalIdentity;
import com.xavier.backend.identity.oidc.model.dto.ExternalIdentityView;
import com.xavier.backend.identity.oidc.repository.ExternalIdentityRepository;
import com.xavier.backend.identity.repository.MfaFactorRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import com.xavier.backend.identity.service.MfaPolicy;
import com.xavier.backend.identity.service.MfaSessionAttributes;
import com.xavier.backend.identity.service.PendingOidcLink;
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

    /** A link request not completed within this is dropped. */
    static final Duration LINK_VALIDITY = Duration.ofMinutes(10);

    private final AccessPolicy accessPolicy;
    private final MfaPolicy mfaPolicy;
    private final MfaFactorRepository factors;
    private final UserAccountRepository accounts;
    private final ExternalIdentityRepository identities;
    private final AuditLog auditLog;
    private final Clock clock;

    ExternalIdentityService(AccessPolicy accessPolicy, MfaPolicy mfaPolicy, MfaFactorRepository factors,
                            UserAccountRepository accounts, ExternalIdentityRepository identities,
                            AuditLog auditLog, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.mfaPolicy = mfaPolicy;
        this.factors = factors;
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
        UserAccount account = linkableAccount(currentAccountId(), session);
        if (identities.existsByAccountId(account.getId())) {
            throw new ExternalIdentityExistsException();
        }
        PendingOidcLink.mark(session, account.getId(), clock.instant());
    }

    /**
     * The account a callback should link to, if this session asked for it in the last 10 minutes; the request is
     * consumed either way (also by a failed callback, {@code OidcLoginHandlers}).
     */
    Optional<UUID> takePendingLink(HttpSession session) {
        return PendingOidcLink.take(session)
                .filter(request -> request.requestedAt().plus(LINK_VALIDITY).isAfter(clock.instant()))
                .map(PendingOidcLink.Request::accountId);
    }

    /**
     * Links {@code (issuer, subject)} to {@code linker}'s own account; audited {@code OIDC_LINKED}. The session must
     * still meet the rule {@link #startLink} checked (D144, amended 2026-11-13): it is read again here, since the
     * owner may have been away for up to 10 minutes. The database decides duplicates, also in a race:
     * {@code UNIQUE (account_id)} and {@code UNIQUE (issuer, subject)}.
     *
     * @throws StepUpRequiredException         the session is no longer a finished login with a fresh second factor
     * @throws ExternalIdentityExistsException the account has a link, or the identity is linked to another account
     */
    @Transactional
    public ExternalIdentityView link(Actor linker, HttpSession session, String issuer, String subject) {
        linkableAccount(linker.userId(), session);
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
        UUID accountId = ownAccount(currentAccountId(), session).getId();
        return identities.findByAccountId(accountId).map(ExternalIdentity::toView)
                .orElseThrow(() -> new ExternalIdentityNotFoundException(accountId));
    }

    /** Removes the caller's own link; audited {@code OIDC_UNLINKED}. Password login stays (P56). */
    @Transactional
    public void unlinkOwn(HttpSession session) {
        unlink(ownAccount(currentAccountId(), session).getId());
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

    private UUID currentAccountId() {
        Actor actor = accessPolicy.current();
        if (actor.kind() != ActorKind.ACCOUNT) {
            throw new AccessDeniedException("Only a BackOffice account can be linked to an identity provider.");
        }
        return actor.userId();
    }

    /**
     * D144 linking rule, at the start and again at the callback: the account's own finished login (not waiting for a
     * second factor or an enrolment, D141, nor for a password change, D30), not a support account (D145), and a fresh
     * second factor while MFA is not OFF (the D142 test). Read from the session and the factor table, not the security
     * context: during the callback that holds Spring's OAuth2 token.
     */
    private UserAccount linkableAccount(UUID accountId, HttpSession session) {
        UserAccount account = ownAccount(accountId, session);
        if (account.isSupport()) {
            throw new SupportAccountCannotLinkException();
        }
        requireFreshSecondFactor(accountId, session);
        return account;
    }

    private UserAccount ownAccount(UUID accountId, HttpSession session) {
        MfaState state = MfaSessionAttributes.state(session);
        if (state == MfaState.PENDING) {
            throw StepUpRequiredException.stepUpRequired();
        }
        if (state == MfaState.ENROLMENT_REQUIRED) {
            throw StepUpRequiredException.enrolmentRequired();
        }
        UserAccount account = accounts.findById(accountId).orElseThrow(() -> new UserNotFoundException(accountId));
        if (account.isMustChangePassword()) {
            throw new AccessDeniedException("Change your password first.");
        }
        return account;
    }

    private void requireFreshSecondFactor(UUID accountId, HttpSession session) {
        if (!mfaPolicy.enforced()) {
            return;
        }
        if (!factors.existsByAccountIdAndStatus(accountId, MfaFactorStatus.ACTIVE)) {
            throw StepUpRequiredException.enrolmentRequired();
        }
        Instant at = MfaSessionAttributes.secondFactorAt(session);
        if (at == null || at.plus(mfaPolicy.stepUpMaxAge()).isBefore(clock.instant())) {
            throw StepUpRequiredException.stepUpRequired();
        }
    }
}
