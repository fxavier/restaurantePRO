package com.xavier.backend.orders.service;

import com.xavier.backend.orders.AdjustmentKind;
import com.xavier.backend.orders.model.OrderLinePriceAdjustment;
import com.xavier.backend.orders.repository.OrderLinePriceAdjustmentRepository;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.promotions.LoyaltyApi;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The one answer to "does this line carry a loyalty reward" (D112, D112a). A line carries one when it holds a REWARD
 * itself or descends, through transfer and split facts made after the reward, from the line that holds it. Every
 * rule that asks (a second reward, a reversal, earning, the bill's label, the void's points) asks here.
 */
@Component
public class RewardLineage {

    private final OrderLinePriceAdjustmentRepository adjustments;
    private final OrderLineRepository lines;
    private final LoyaltyApi loyalty;

    RewardLineage(OrderLinePriceAdjustmentRepository adjustments, OrderLineRepository lines, LoyaltyApi loyalty) {
        this.adjustments = adjustments;
        this.lines = lines;
        this.loyalty = loyalty;
    }

    /** The line that holds the reward {@code lineId} carries; empty when it carries none. */
    public Optional<UUID> holderOf(UUID lineId) {
        return adjustments.findRewardOrigins(List.of(lineId)).stream()
                .map(OrderLinePriceAdjustmentRepository.RewardOrigin::getRewardedLineId).findFirst();
    }

    public boolean isRewarded(UUID lineId) {
        return holderOf(lineId).isPresent();
    }

    /** Whether a reward was redeemed on the order itself (its customer is then fixed, D112). */
    public boolean redeemedOn(UUID orderId) {
        return adjustments.existsByOrderIdAndKind(orderId, AdjustmentKind.REWARD);
    }

    /** Those of {@code lineIds} that carry a reward. */
    public Set<UUID> rewarded(Collection<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return Set.of();
        }
        return adjustments.findRewardOrigins(lineIds).stream()
                .map(OrderLinePriceAdjustmentRepository.RewardOrigin::getLineId).collect(Collectors.toSet());
    }

    /** The reward code each of {@code lineIds} carries from the line that holds it (the bill's label). */
    public Map<UUID, String> rewardCodes(Collection<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return Map.of();
        }
        List<OrderLinePriceAdjustmentRepository.RewardOrigin> origins = adjustments.findRewardOrigins(lineIds);
        Map<UUID, String> codes = adjustments.findByLineIdInAndKind(origins.stream()
                        .map(OrderLinePriceAdjustmentRepository.RewardOrigin::getRewardedLineId).toList(),
                        AdjustmentKind.REWARD).stream()
                .collect(Collectors.toMap(OrderLinePriceAdjustment::getLineId,
                        OrderLinePriceAdjustment::getRewardCode, (first, second) -> first));
        return origins.stream().filter(origin -> codes.containsKey(origin.getRewardedLineId()))
                .collect(Collectors.toMap(OrderLinePriceAdjustmentRepository.RewardOrigin::getLineId,
                        origin -> codes.get(origin.getRewardedLineId())));
    }

    /**
     * D112a, after a void of {@code voidedLineId} was recorded: when the reward's lineage is now at net 0, its
     * redemption goes back, exactly once. The holder's row is locked first ({@code FOR NO KEY UPDATE}, after the
     * order's version claim, D132), so two voids of copies in two orders queue and the second reads, in a new
     * statement, the net the first committed (D133).
     */
    public void reverseIfSpent(UUID voidedLineId, UUID voidId) {
        holderOf(voidedLineId).ifPresent(holder -> {
            lines.lockForRewardLineage(holder);
            if (adjustments.rewardLineageNet(holder).signum() == 0) {
                loyalty.reverseRedemption(holder, voidId);
            }
        });
    }
}
