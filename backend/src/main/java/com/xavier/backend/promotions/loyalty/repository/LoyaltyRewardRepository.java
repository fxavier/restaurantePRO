package com.xavier.backend.promotions.loyalty.repository;

import com.xavier.backend.promotions.loyalty.model.LoyaltyReward;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoyaltyRewardRepository extends JpaRepository<LoyaltyReward, UUID> {

    List<LoyaltyReward> findByVersionIdOrderByCode(UUID versionId);

    List<LoyaltyReward> findByVersionIdInOrderByCode(Collection<UUID> versionIds);

    Optional<LoyaltyReward> findByVersionIdAndCode(UUID versionId, String code);

    Optional<LoyaltyReward> findByIdAndVersionId(UUID id, UUID versionId);
}
