package com.xavier.backend.inventory.model.dto;

import com.xavier.backend.inventory.model.MovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One movement to post through {@code StockLedger.post} (D113), the only posting path (S42, used by S47–S49).
 *
 * @param quantity  signed, in the item's base unit, with the sign its type requires (0 for a REVALUATION)
 * @param unitCost  an IN's own cost (a receipt, a transfer IN, an opening), or {@code null} to value it at the current
 *                  average. An OUT is always valued at the average (weighted-average costing, D113); a cost given
 *                  for an OUT (a supplier return at its receipt's cost, a shortage) is stored for reference only
 * @param value     a REVALUATION's value (signed, scale 4); or the exact value (0 or more) of an IN at its own cost
 *                  instead of {@code round(q × unitCost, 4)}: a CONSUMPTION_REVERSAL giving back what is left of a
 *                  sale's value, a TRANSFER_IN carrying its TRANSFER_OUT's value (S47); {@code null} otherwise
 * @param sourceType what the movement comes from ({@code ORDER_LINE}, {@code GOODS_RECEIPT}, {@code TRANSFER}…);
 *                  with {@code sourceId}, item, type and warehouse it is the idempotency key (§8.2)
 * @param occurredAt the economic instant (a line's confirmation, a receipt's confirmation, a count's cut-off)
 * @param manual     a manual outgoing movement, refused below zero in a {@code REFUSE_MANUAL} warehouse (D118)
 */
public record PostMovement(UUID warehouseId, UUID stockItemId, MovementType type, BigDecimal quantity,
                           BigDecimal unitCost, BigDecimal value, String sourceType, UUID sourceId,
                           UUID sourceLineId, UUID reversesMovementId, UUID recipeVersionId, UUID policyVersionId,
                           UUID saleItemId, LocalDate businessDate, Instant occurredAt, String reason,
                           boolean manual) {

    public PostMovement {
        Objects.requireNonNull(warehouseId, "warehouseId");
        Objects.requireNonNull(stockItemId, "stockItemId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (!type.accepts(quantity)) {
            throw new IllegalArgumentException(type + " does not move a quantity of " + quantity);
        }
        if (type == MovementType.REVALUATION && value == null) {
            throw new IllegalArgumentException("a REVALUATION is given its value");
        }
        boolean exactIn = type == MovementType.CONSUMPTION_REVERSAL || type == MovementType.TRANSFER_IN;
        if (value != null && type != MovementType.REVALUATION && (!exactIn || unitCost == null || value.signum() < 0)) {
            throw new IllegalArgumentException("only a REVALUATION, or a CONSUMPTION_REVERSAL or TRANSFER_IN at its own "
                    + "cost with a value of 0 or more, is given a value");
        }
        if (unitCost != null && unitCost.signum() < 0) {
            throw new IllegalArgumentException("a unit cost is never negative");
        }
        requireScale(quantity, 6, "a quantity");
        requireScale(unitCost, 6, "a unit cost");
        requireScale(value, 4, "a value");
    }

    /** Callers that take amounts from a request check them first and answer with a coded 422. */
    private static void requireScale(BigDecimal number, int decimals, String what) {
        if (number != null && number.stripTrailingZeros().scale() > decimals) {
            throw new IllegalArgumentException(what + " has at most " + decimals + " decimals, was " + number);
        }
    }

    /** A movement valued at the current average, with no optional reference. */
    public static PostMovement of(UUID warehouseId, UUID stockItemId, MovementType type, BigDecimal quantity,
                                  String sourceType, UUID sourceId, LocalDate businessDate, Instant occurredAt) {
        return new PostMovement(warehouseId, stockItemId, type, quantity, null, null, sourceType, sourceId, null,
                null, null, null, null, businessDate, occurredAt, null, false);
    }

    /** A value-only movement (a cost correction, D125). */
    public static PostMovement revaluation(UUID warehouseId, UUID stockItemId, BigDecimal value, String sourceType,
                                           UUID sourceId, LocalDate businessDate, Instant occurredAt) {
        return new PostMovement(warehouseId, stockItemId, MovementType.REVALUATION, BigDecimal.ZERO, null, value,
                sourceType, sourceId, null, null, null, null, null, businessDate, occurredAt, null, false);
    }

    public PostMovement atCost(BigDecimal cost) {
        return new PostMovement(warehouseId, stockItemId, type, quantity, cost, value, sourceType, sourceId,
                sourceLineId, reversesMovementId, recipeVersionId, policyVersionId, saleItemId, businessDate,
                occurredAt, reason, manual);
    }

    /** A manual movement with its reason: refused below zero under {@code REFUSE_MANUAL} when outgoing. */
    public PostMovement manual(String why) {
        return new PostMovement(warehouseId, stockItemId, type, quantity, unitCost, value, sourceType, sourceId,
                sourceLineId, reversesMovementId, recipeVersionId, policyVersionId, saleItemId, businessDate,
                occurredAt, why, true);
    }

    /** A CONSUMPTION_REVERSAL or TRANSFER_IN at its own cost carrying exactly {@code exact} (scale 4, 0 or more). */
    public PostMovement withValue(BigDecimal exact) {
        return new PostMovement(warehouseId, stockItemId, type, quantity, unitCost, exact, sourceType, sourceId,
                sourceLineId, reversesMovementId, recipeVersionId, policyVersionId, saleItemId, businessDate,
                occurredAt, reason, manual);
    }

    public PostMovement withReason(String why) {
        return new PostMovement(warehouseId, stockItemId, type, quantity, unitCost, value, sourceType, sourceId,
                sourceLineId, reversesMovementId, recipeVersionId, policyVersionId, saleItemId, businessDate,
                occurredAt, why, manual);
    }

    public PostMovement withSourceLine(UUID lineId) {
        return new PostMovement(warehouseId, stockItemId, type, quantity, unitCost, value, sourceType, sourceId,
                lineId, reversesMovementId, recipeVersionId, policyVersionId, saleItemId, businessDate, occurredAt,
                reason, manual);
    }

    /** The sale item, recipe version and consumption policy version a consumption comes from (S47). */
    public PostMovement fromSale(UUID saleItem, UUID recipeVersion, UUID policyVersion) {
        return new PostMovement(warehouseId, stockItemId, type, quantity, unitCost, value, sourceType, sourceId,
                sourceLineId, reversesMovementId, recipeVersion, policyVersion, saleItem, businessDate, occurredAt,
                reason, manual);
    }

    public PostMovement reversing(UUID movementId) {
        return new PostMovement(warehouseId, stockItemId, type, quantity, unitCost, value, sourceType, sourceId,
                sourceLineId, movementId, recipeVersionId, policyVersionId, saleItemId, businessDate, occurredAt,
                reason, manual);
    }
}
