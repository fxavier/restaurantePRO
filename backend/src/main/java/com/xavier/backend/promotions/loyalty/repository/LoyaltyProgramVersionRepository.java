package com.xavier.backend.promotions.loyalty.repository;

import com.xavier.backend.promotions.loyalty.model.LoyaltyProgramVersion;
import com.xavier.backend.promotions.loyalty.model.ProgramStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoyaltyProgramVersionRepository extends JpaRepository<LoyaltyProgramVersion, UUID> {

    @Query("SELECT coalesce(max(v.versionNo), 0) FROM LoyaltyProgramVersion v")
    int maxVersionNo();

    Optional<LoyaltyProgramVersion> findByStatus(ProgramStatus status);

    List<LoyaltyProgramVersion> findAllByOrderByVersionNoDesc();

    /** The version in force at {@code at}: published by then and not yet retired (D111). */
    @Query("""
            SELECT v FROM LoyaltyProgramVersion v
            WHERE v.publishedAt <= :at AND (v.retiredAt IS NULL OR v.retiredAt > :at)""")
    Optional<LoyaltyProgramVersion> inForceAt(@Param("at") Instant at);
}
