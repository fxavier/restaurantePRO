package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.SupportAccountRequiredException;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.tenancy.TenancyApi;
import com.xavier.backend.shared.exception.UnavailableException;
import com.xavier.backend.tenancy.model.dto.MembershipGrant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import java.time.OffsetDateTime;
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
 * SUPPORT_ACCESS_MANAGE}, tenant-wide) grants a support account access from now for at most 24 h, and may revoke it
 * early. The grant names the support person's ops-realm subject and creates, in the same transaction, a {@code SUPPORT}
 * membership valid exactly as long (D183): only that person, with an ops-realm token and a second factor, acts in
 * this tenant, as the support account. Revoking the grant revokes the membership (refused on the next request); its
 * end needs no job (the membership's {@code valid_until}). Every grant and revoke is audited; never a secret.
 */
@Service
public class SupportGrantService {

    private static final Logger log = LoggerFactory.getLogger(SupportGrantService.class);
    private static final String ENTITY = "SupportGrant";

    private final SupportGrantRepository grants;
    private final UserAccountRepository accounts;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final TenancyApi tenancy;
    private final String opsIssuer;
    private final Clock clock;
    private final JdbcClient jdbc;

    SupportGrantService(SupportGrantRepository grants, UserAccountRepository accounts, AccessPolicy accessPolicy,
                        AuditLog auditLog, TenancyApi tenancy,
                        @Value("${restaurantpro.security.issuers.ops:}") String opsIssuer, Clock clock,
                        JdbcClient jdbc) {
        this.grants = grants;
        this.accounts = accounts;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.tenancy = tenancy;
        this.opsIssuer = opsIssuer;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    @Transactional
    public SupportGrantSummary grant(SupportGrantRequest request) {
        Actor granter = requireGranter();
        UserAccount account = accounts.findById(request.accountId())
                .orElseThrow(() -> new UserNotFoundException(request.accountId()));
        if (!account.isSupport()) {
            throw new SupportAccountRequiredException();
        }
        Instant now = transactionStart();
        Optional<SupportGrant> current = grants.findByAccountIdAndRevokedAtIsNullAndExpiredAtIsNull(account.getId());
        if (current.isPresent() && current.get().isUsableAt(now)) {
            throw new SupportGrantActiveException();
        }
        current.ifPresent(ended -> expire(ended.getId())); // past its end, not yet swept by the job
        SupportGrant grant = UniqueConstraints.saveAndFlush(grants,
                SupportGrant.grant(account.getId(), request.subject().strip(),
                        Duration.ofMinutes(request.durationMinutes()), request.reason().strip(), granter.userId(), now),
                SupportGrantActiveException::new, "support_grant_one_active_uk");
        if (opsIssuer.isBlank()) {
            throw new UnavailableException("IDP_UNAVAILABLE", "The support realm is not configured.", Map.of());
        }
        grant.linkMembership(tenancy.grant(new MembershipGrant(opsIssuer, grant.getSubject(), MembershipKind.SUPPORT,
                grant.getId(), Duration.ofMinutes(request.durationMinutes()))));
        grants.saveAndFlush(grant);
        auditLog.recordAsCurrentActor("SUPPORT_GRANT_CREATED", ENTITY, grant.getId().toString(), null, null,
                grant.getReason(), Map.of("accountId", account.getId(), "validFrom", now,
                        "validUntil", grant.getValidUntil()));
        return grant.toSummary(now);
    }

    /**
     * The database's {@code now()}, fixed for the transaction: the instant {@code tenancy.grant_membership} dates the
     * membership from (V15003), so the grant, its audit row and its membership name one window even when the
     * application's clock disagrees with the database's.
     */
    private Instant transactionStart() {
        return jdbc.sql("SELECT pg_catalog.now()").query(OffsetDateTime.class).single().toInstant();
    }

    /** Ends the grant now, and its membership: support is refused on its next request. */
    @Transactional
    public SupportGrantSummary revoke(UUID grantId) {
        Actor revoker = requireGranter();
        SupportGrant grant = grants.findById(grantId).orElseThrow(() -> new SupportGrantNotFoundException(grantId));
        Instant now = clock.instant();
        grant.revoke(revoker.userId(), now);
        grants.saveAndFlush(grant);
        if (grant.getMembershipId() != null) {
            tenancy.revoke(grant.getMembershipId());
        }
        auditLog.recordAsCurrentActor("SUPPORT_GRANT_REVOKED", ENTITY, grantId.toString(), null, null, null,
                Map.of("accountId", grant.getAccountId()));
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
     * Expires one grant past its end (compare-and-set, so a revoke that got there first changes nothing) and audits as
     * the system; its membership ended by itself at {@code valid_until}.
     *
     * @return whether this call expired it
     */
    @Transactional
    public boolean expire(UUID grantId) {
        if (grants.markExpired(grantId, clock.instant()) == 0) {
            return false;
        }
        UUID accountId = grants.findById(grantId).orElseThrow().getAccountId();
        auditLog.recordAsSystem("SUPPORT_GRANT_EXPIRED", ENTITY, grantId.toString(), null, null, null,
                Map.of("accountId", accountId));
        return true;
    }

    /**
     * {@code SUPPORT_ACCESS_MANAGE} tenant-wide, held by a BackOffice account that is not itself a support
     * account. A support actor never holds the permission ({@code AuthorityResolver}); this check keeps that true
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
