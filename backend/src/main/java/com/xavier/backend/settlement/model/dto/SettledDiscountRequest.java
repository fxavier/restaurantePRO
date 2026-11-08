package com.xavier.backend.settlement.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xavier.backend.orders.AdjustmentKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/pos/orders/{id}/settlement/discounts} (D105): the orders route's body, at the order version the
 * POS read. {@code kind} DISCOUNT takes a {@code percent} or a new {@code unitPrice}; OFFER no value; REWARD a
 * {@code rewardCode} and one unit. A REVERSAL raises the bill, so this route refuses it (422
 * {@code PRICE_INCREASE_NOT_ALLOWED}). A line's {@code quantity} below its net quantity splits it first; absent = all
 * of it. The approver comes in the D40 headers.
 */
public record SettledDiscountRequest(
        @NotNull Long orderVersion,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Line> lines,
        @NotNull AdjustmentKind kind,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") @Digits(integer = 3, fraction = 4)
        BigDecimal percent,
        @DecimalMin("0") @Digits(integer = 15, fraction = 2) BigDecimal unitPrice,
        @Size(max = 30) String rewardCode,
        @NotBlank @Size(max = 200) String reason) {

    /** DISCOUNT: exactly one of percent and unitPrice; REWARD: a reward code only; OFFER: no value. */
    @JsonIgnore
    @AssertTrue(message = "the values do not match the kind")
    public boolean isValuesMatchTheKind() {
        if (kind == null) {
            return true;
        }
        return switch (kind) {
            case DISCOUNT -> (percent == null) != (unitPrice == null) && rewardCode == null;
            case OFFER -> percent == null && unitPrice == null && rewardCode == null;
            case REWARD -> percent == null && unitPrice == null && rewardCode != null && !rewardCode.isBlank();
            case REVERSAL -> percent == null && rewardCode == null;
        };
    }

    public record Line(@NotNull UUID lineId,
                       @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                       BigDecimal quantity) {
    }
}
