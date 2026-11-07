package com.xavier.backend.promotions.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A new promotion. {@code percent} goes with PERCENT_OFF, {@code amount} (in the company currency) with the other
 * kinds; {@code modes} null applies to every order mode; {@code active} defaults to true.
 */
public record PromotionCreateRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull @Pattern(regexp = PromotionUpdateRequest.KINDS) String kind,
        BigDecimal percent,
        BigDecimal amount,
        @NotNull Integer priority,
        Instant validFrom,
        Instant validTo,
        Boolean membersOnly,
        List<@NotBlank @Size(max = 20) String> modes,
        Boolean active) {
}
