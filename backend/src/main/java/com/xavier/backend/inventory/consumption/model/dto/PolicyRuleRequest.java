package com.xavier.backend.inventory.consumption.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** {@code familyId} absent = the default rule; {@code trigger} is ROUND_CONFIRMED (the only one in R4) when absent. */
public record PolicyRuleRequest(UUID familyId, @NotNull Boolean tracked,
                                @Pattern(regexp = "ROUND_CONFIRMED") String trigger) {
}
