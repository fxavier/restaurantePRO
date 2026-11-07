package com.xavier.backend.promotions.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The new terms of a promotion (its code never changes) at the {@code version} the client read. */
public record PromotionUpdateRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull @Pattern(regexp = KINDS) String kind,
        BigDecimal percent,
        BigDecimal amount,
        @NotNull Integer priority,
        Instant validFrom,
        Instant validTo,
        @NotNull Boolean membersOnly,
        List<@NotBlank @Size(max = 20) String> modes,
        @NotNull Boolean active,
        @NotNull Long version) {

    static final String KINDS = "PERCENT_OFF|AMOUNT_OFF_UNIT|FIXED_UNIT_PRICE";
}
