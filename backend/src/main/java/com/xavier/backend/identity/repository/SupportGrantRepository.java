package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.SupportGrant;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SupportGrantRepository extends JpaRepository<SupportGrant, UUID> {

    /** The account's ACTIVE grant (neither revoked nor expired by the job); its end may already have passed. */
    Optional<SupportGrant> findByAccountIdAndRevokedAtIsNullAndExpiredAtIsNull(UUID accountId);

    Page<SupportGrant> findByAccountId(UUID accountId, Pageable pageable);

    /** The grants support is acting on now (used, not ended): the {@code SUPPORT_ACCESS_ACTIVE} alert. */
    @Query("select g from SupportGrant g where g.firstUsedAt is not null and g.revokedAt is null "
            + "and g.expiredAt is null and g.validFrom <= :now and g.validUntil > :now")
    List<SupportGrant> findInUse(Instant now);

    /**
     * Compare-and-set ACTIVE → EXPIRED; moves the version, so a concurrent revoke of the loaded grant gets a 409.
     *
     * @return 1 when this call expired it, 0 when it had already ended
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SupportGrant g set g.expiredAt = :now, g.version = g.version + 1 "
            + "where g.id = :id and g.revokedAt is null and g.expiredAt is null and g.validUntil <= :now")
    int markExpired(UUID id, Instant now);
}
