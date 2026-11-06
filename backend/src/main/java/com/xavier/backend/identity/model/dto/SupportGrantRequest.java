package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Grants support access to a support account from now for {@code durationMinutes} (at most 24 h, P36). */
public record SupportGrantRequest(
        @NotNull UUID accountId,
        @NotNull @Min(1) @Max(1440) Integer durationMinutes,
        @NotBlank @Size(max = 500) String reason) {
}
