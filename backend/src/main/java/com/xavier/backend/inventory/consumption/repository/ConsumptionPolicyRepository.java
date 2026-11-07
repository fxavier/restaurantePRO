package com.xavier.backend.inventory.consumption.repository;

import com.xavier.backend.inventory.consumption.model.ConsumptionPolicyVersion;
import com.xavier.backend.inventory.recipe.model.VersionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConsumptionPolicyRepository extends JpaRepository<ConsumptionPolicyVersion, UUID> {

    List<ConsumptionPolicyVersion> findAllByOrderByVersionNo();

    Optional<ConsumptionPolicyVersion> findByVersionNo(int versionNo);

    Optional<ConsumptionPolicyVersion> findByPeriodStatus(VersionStatus status);

    @Query("SELECT coalesce(max(v.versionNo), 0) FROM ConsumptionPolicyVersion v")
    int maxVersionNo();

    @Query("""
            SELECT v FROM ConsumptionPolicyVersion v
            WHERE v.period.status <> com.xavier.backend.inventory.recipe.model.VersionStatus.DRAFT
              AND v.period.effectiveFrom <= :at AND (v.period.effectiveTo IS NULL OR v.period.effectiveTo > :at)""")
    Optional<ConsumptionPolicyVersion> effectiveAt(Instant at);
}
