package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.MfaFactor;
import com.xavier.backend.identity.model.MfaFactorStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MfaFactorRepository extends JpaRepository<MfaFactor, UUID> {

    Optional<MfaFactor> findByAccountIdAndStatus(UUID accountId, MfaFactorStatus status);

    boolean existsByAccountIdAndStatus(UUID accountId, MfaFactorStatus status);

    /**
     * The factor row, locked ({@code FOR UPDATE}) for a code check: two sessions using the same code at once are
     * serialized, and the second sees the first's {@code last_used_step} (D139 replay, also concurrent).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from MfaFactor f where f.accountId = :accountId and f.status = :status")
    Optional<MfaFactor> lockByAccountIdAndStatus(@Param("accountId") UUID accountId,
                                                 @Param("status") MfaFactorStatus status);

    @Query("select f.accountId from MfaFactor f where f.status = 'ACTIVE' and f.accountId in :accountIds")
    Set<UUID> findEnrolledAmong(@Param("accountIds") Collection<UUID> accountIds);

    /** A PENDING factor is replaced or dropped, never revoked: it proved nothing. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MfaFactor f where f.accountId = :accountId and f.status = 'PENDING'")
    int deletePending(@Param("accountId") UUID accountId);
}
