package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.MfaEnrolmentCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MfaEnrolmentCodeRepository extends JpaRepository<MfaEnrolmentCode, UUID> {

    boolean existsByAccountIdAndCodeHashAndUsedAtIsNullAndExpiresAtAfter(UUID accountId, String codeHash,
                                                                         Instant now);

    /** Compare-and-set: 1 when the account's open, unexpired code with this hash is now used (D143: once). */
    @Modifying(flushAutomatically = true)
    @Query("update MfaEnrolmentCode c set c.usedAt = :now where c.accountId = :accountId "
            + "and c.codeHash = :codeHash and c.usedAt is null and c.expiresAt > :now")
    int consume(@Param("accountId") UUID accountId, @Param("codeHash") String codeHash, @Param("now") Instant now);

    /** A newer reset voids the account's open code. */
    @Modifying(flushAutomatically = true)
    @Query("update MfaEnrolmentCode c set c.usedAt = :now where c.accountId = :accountId and c.usedAt is null")
    int voidOpen(@Param("accountId") UUID accountId, @Param("now") Instant now);

    Optional<MfaEnrolmentCode> findByAccountIdAndUsedAtIsNull(UUID accountId);
}
