package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.AdjustmentKind;
import jakarta.validation.Valid;
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
 * A price adjustment of confirmed lines (D105, D106, D112). {@code kind} DISCOUNT takes a {@code percent} (off the
 * current price, HALF_UP per unit, D101) or a new {@code unitPrice}; OFFER sets 0; REWARD takes a {@code rewardCode}
 * and one unit; REVERSAL takes a {@code unitPrice} (default: the list price). A line's {@code quantity} below its net
 * quantity splits it first; absent = all of it. The approver travels in the {@code X-Approver-Code} and
 * {@code X-Approver-Pin} headers, never in this body (D40).
 */
public record DiscountRequest(
        @NotNull Long orderVersion,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Line> lines,
        @NotNull AdjustmentKind kind,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") @Digits(integer = 3, fraction = 4)
        BigDecimal percent,
        @DecimalMin("0") @Digits(integer = 15, fraction = 2) BigDecimal unitPrice,
        @Size(max = 30) String rewardCode,
        @NotBlank @Size(max = 200) String reason) {

    public record Line(@NotNull UUID lineId,
                       @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                       BigDecimal quantity) {
    }
}
