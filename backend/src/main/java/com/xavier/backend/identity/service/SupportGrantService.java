package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.SupportAccountRequiredException;
import com.xavier.backend.identity.exception.SupportGrantActiveException;
import com.xavier.backend.identity.exception.SupportGrantNotFoundException;
import com.xavier.backend.identity.exception.UserNotFoundException;
import com.xavier.backend.identity.model.SupportGrant;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.SupportGrantRequest;
import com.xavier.backend.identity.model.dto.SupportGrantSummary;
import com.xavier.backend.identity.repository.SupportGrantRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Support access (spec §5 "temporário, autorizado e auditado", P36, D83). An administrator ({@code
 * SUPPORT_ACCESS_MANAGE}, installation-wide) grants a support account access from now for at most 24 h, and may
 * revoke it early. A grant that ends (revoke here, expiry in {@link SupportGrantExpiry}) ends the account's sessions.
 * Every grant, revoke and expiry is audited; the details carry ids, instants and a session count, never a secret.
 */
@Service
public class SupportGrantService {

    private static final Logger log = LoggerFactory.getLogger(SupportGrantService.class);
    private static final String ENTITY = "SupportGrant";

    private final SupportGrantRepository grants;
    private final UserAccountRepository accounts;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final SessionTermination sessions;
    private final Clock clock;

    SupportGrantService(SupportGrantRepository grants, UserAccountRepository accounts, AccessPolicy accessPolicy,
                        AuditLog auditLog, SessionTermination sessions, Clock clock) {
        this.grants = grants;
        this.accounts = accounts;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public SupportGrantSummary grant(SupportGrantRequest request) {
        Actor granter = requireGranter();
        UserAccount account = accounts.findById(request.accountId())
                .orElseThrow(() -> new UserNotFoundException(request.accountId()));
        if (!account.isSupport()) {
            throw new SupportAccountRequiredException();
        }
        Instant now = clock.instant();
        Optional<SupportGrant> current = grants.findByAccountIdAndRevokedAtIsNullAndExpiredAtIsNull(account.getId());
        if (current.isPresent() && current.get().isUsableAt(now)) {
            throw new SupportGrantActiveException();
        }
        current.ifPresent(ended -> expire(ended.getId())); // past its end, not yet swept by the job
        SupportGrant grant = UniqueConstraints.saveAndFlush(grants,
                SupportGrant.grant(account.getId(), Duration.ofMinutes(request.durationMinutes()),
                        request.reason().strip(), granter.userId(), now),
                SupportGrantActiveException::new, "support_grant_one_active_uk");
        auditLog.recordAsCurrentActor("SUPPORT_GRANT_CREATED", ENTITY, grant.getId().toString(), null, null,
                grant.getReason(), Map.of("accountId", account.getId(), "validFrom", now,
                        "validUntil", grant.getValidUntil()));
        return grant.toSummary(now);
    }

    /** Ends the grant now and the account's sessions with it. */
    @Transactional
    public SupportGrantSummary revoke(UUID grantId) {
        Actor revoker = requireGranter();
        SupportGrant grant = grants.findById(grantId).orElseThrow(() -> new SupportGrantNotFoundException(grantId));
        Instant now = clock.instant();
        grant.revoke(revoker.userId(), now);
        grants.saveAndFlush(grant);
        int ended = sessions.endAccountSessions(grant.getAccountId());
        auditLog.recordAsCurrentActor("SUPPORT_GRANT_REVOKED", ENTITY, grantId.toString(), null, null, null,
                Map.of("accountId", grant.getAccountId(), "endedSessions", ended));
        return grant.toSummary(now);
    }

    /** Newest first unless the request sorts; {@code accountId} narrows to one account. */
    @Transactional(readOnly = true)
    public PageResponse<SupportGrantSummary> list(UUID accountId, Pageable pageable) {
        accessPolicy.require(Permission.SUPPORT_ACCESS_MANAGE);
        Pageable page = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("validFrom").descending());
        Instant now = clock.instant();
        return PageResponse.from((accountId == null ? grants.findAll(page) : grants.findByAccountId(accountId, page))
                .map(grant -> grant.toSummary(now)));
    }

    /**
     * The grant a support account may log in with now, if any. Not authorized: it is a step of login, which has no
     * actor yet.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> usableGrantOf(UUID accountId) {
        Instant now = clock.instant();
        return grants.findByAccountIdAndRevokedAtIsNullAndExpiredAtIsNull(accountId)
                .filter(grant -> grant.isUsableAt(now))
                .map(SupportGrant::getId);
    }

    /**
     * The grant a new session of this account rides on: empty for an ordinary account. For a support account without
     * a usable grant (a race with the grant's end) the session is dead at once: no grant id, ended now.
     */
    @Transactional(readOnly = true)
    public Optional<SupportSession> sessionGrantOf(UUID accountId) {
        if (!accounts.findById(accountId).map(UserAccount::isSupport).orElse(false)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return Optional.of(grants.findByAccountIdAndRevokedAtIsNullAndExpiredAtIsNull(accountId)
                .filter(grant -> grant.isUsableAt(now))
                .map(grant -> new SupportSession(grant.getId(), grant.getValidUntil(), grant.remainingAt(now)))
                .orElse(new SupportSession(null, now, Duration.ZERO)));
    }

    /**
     * Whether a support session may serve this request: its grant has not reached {@code validUntil} and is still
     * ACTIVE (one primary-key read). Sessions are also deleted when a grant ends, but a revoke that commits while a
     * login is still saving its session finds nothing to delete; this check does not depend on that.
     */
    @Transactional(readOnly = true)
    public boolean sessionGrantHolds(UUID grantId, Instant validUntil) {
        Instant now = clock.instant();
        if (grantId == null || !now.isBefore(validUntil)) {
            return false;
        }
        return grants.findById(grantId).map(grant -> grant.isUsableAt(now)).orElse(false);
    }

    /** The grant a support session was opened with, and how long the session may still stay idle. */
    public record SupportSession(UUID grantId, Instant validUntil, Duration remaining) {
    }

    /** ACTIVE grants whose end has come, oldest end first. */
    @Transactional(readOnly = true)
    List<UUID> dueGrantIds() {
        return grants.findIdsDue(clock.instant());
    }

    /**
     * Expires one due grant (compare-and-set, so an overlapping run or a revoke that got there first changes
     * nothing), ends its sessions and audits as the system.
     *
     * @return whether this call expired it
     */
    @Transactional
    public boolean expire(UUID grantId) {
        if (grants.markExpired(grantId, clock.instant()) == 0) {
            return false;
        }
        UUID accountId = grants.findById(grantId).orElseThrow().getAccountId();
        int ended = sessions.endAccountSessions(accountId);
        auditLog.recordAsSystem("SUPPORT_GRANT_EXPIRED", ENTITY, grantId.toString(), null, null, null,
                Map.of("accountId", accountId, "endedSessions", ended));
        return true;
    }

    /**
     * {@code SUPPORT_ACCESS_MANAGE} installation-wide, held by a BackOffice account that is not itself a support
     * account. A support session never holds the permission ({@code AuthorityResolver}); this check keeps that true
     * even if a session somehow did.
     */
    private Actor requireGranter() {
        accessPolicy.require(Permission.SUPPORT_ACCESS_MANAGE);
        Actor actor = accessPolicy.current();
        boolean ordinaryAccount = actor.kind() == ActorKind.ACCOUNT
                && accounts.findById(actor.userId()).map(account -> !account.isSupport()).orElse(false);
        if (!ordinaryAccount) {
            log.warn("Support access management denied to {} {}: not an ordinary BackOffice account", actor.kind(),
                    actor.userId());
            throw new AccessDeniedException("Support access is managed only by an ordinary BackOffice account.");
        }
        return actor;
    }
}
