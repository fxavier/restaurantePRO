package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.MfaRecoveryCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

    /** At most 10: the caller compares each hash with the given code in constant time. */
    List<MfaRecoveryCode> findByFactorIdAndUsedAtIsNull(UUID factorId);

    /** Compare-and-set: 1 when this code was unused and is now used, 0 otherwise (D139: once). */
    @Modifying(flushAutomatically = true)
    @Query("update MfaRecoveryCode c set c.usedAt = :now where c.id = :id and c.usedAt is null")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    long countByFactorIdAndUsedAtIsNull(UUID factorId);

    /** Regenerating replaces every code of the factor. */
    @Modifying(flushAutomatically = true)
    @Query("delete from MfaRecoveryCode c where c.factorId = :factorId")
    int deleteByFactor(@Param("factorId") UUID factorId);
}
