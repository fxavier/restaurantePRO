package com.xavier.backend.promotions.loyalty.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A program version with its rule, scope and rewards. */
public record ProgramVersionView(UUID id, int versionNo, String status, Money spendUnit, int pointsPerUnit,
                                 List<UUID> itemIds, List<UUID> familyIds, List<RewardView> rewards,
                                 Instant createdAt, Instant publishedAt, UUID publishedByAccountId, Instant retiredAt,
                                 long version) {
}
