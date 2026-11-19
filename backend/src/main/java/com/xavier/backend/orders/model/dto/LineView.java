package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.model.LineKind;
import com.xavier.backend.orders.model.LineStatus;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A line as the POS shows it. A DRAFT carries a preview ({@code preview = true}): the price the current catalog and
 * promotions would give it now (D103), {@code null} when the item left the catalog, and no snapshot. A CONFIRMED line
 * carries its snapshot, its net quantity and its gross for that net quantity (D59).
 *
 * <p>R4: {@code unitPrice} is the effective price, {@code listUnitPrice} the catalog's, {@code discount} the gross
 * the difference takes off the net quantity (D101); {@code origin} ({@code PROMOTION}, {@code MANUAL}, {@code OFFER},
 * {@code LOYALTY}) and {@code discountLabel} are {@code null} at list price. {@code components} are a menu's choices
 * (D108); {@code adjustments} the line's price adjustments in order (D105).
 */
@NullMarked
public record LineView(UUID id, LineStatus status, UUID itemId, @Nullable UUID variantId, BigDecimal quantity,
                       @Nullable BigDecimal netQuantity, @Nullable String notes, long version,
                       @Nullable UUID batchId, @Nullable String itemCode, @Nullable String description,
                       @Nullable String variantName, @Nullable Money unitPrice, @Nullable BigDecimal taxRatePercent,
                       @Nullable Money gross, boolean preview, @Nullable LineKind kind, boolean automatic,
                       @Nullable Money listUnitPrice, @Nullable Money discount, @Nullable String origin,
                       @Nullable String discountLabel, @Nullable String promotionCode, @Nullable String promotionName,
                       List<ComponentView> components, List<AdjustmentView> adjustments) {

    /** A menu's choice; code, description and destination are set once the line is confirmed. */
    public record ComponentView(UUID id, String groupCode, UUID itemId, @Nullable UUID variantId,
                                @Nullable String itemCode, @Nullable String description, BigDecimal quantity,
                                @Nullable String destination) {
    }

    /** One price adjustment of a confirmed line (D105). */
    public record AdjustmentView(UUID id, int seq, String kind, BigDecimal quantity, @Nullable BigDecimal percent,
                                 Money previousUnitPrice, Money unitPrice, @Nullable String rewardCode, String reason,
                                 UUID operatorEmployeeId, @Nullable UUID approverEmployeeId, java.time.Instant at) {
    }
}
