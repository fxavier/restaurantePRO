package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.MfaRecoveryCode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

    /** Compare-and-set: 1 when this unused code of the factor is now used, 0 otherwise (D139: once). */
    @Modifying(flushAutomatically = true)
    @Query("update MfaRecoveryCode c set c.usedAt = :now where c.factorId = :factorId and c.codeHash = :codeHash "
            + "and c.usedAt is null")
    int consume(@Param("factorId") UUID factorId, @Param("codeHash") String codeHash, @Param("now") Instant now);

    long countByFactorIdAndUsedAtIsNull(UUID factorId);

    /** Regenerating replaces every code of the factor. */
    @Modifying(flushAutomatically = true)
    @Query("delete from MfaRecoveryCode c where c.factorId = :factorId")
    int deleteByFactor(@Param("factorId") UUID factorId);
}
