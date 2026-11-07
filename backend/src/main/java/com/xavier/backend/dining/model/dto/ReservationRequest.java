package com.xavier.backend.dining.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Book a reservation (D126). The store is the POS session's (D39). No overlap or capacity check (§4); the tables
 * are informative. {@code contactPhone} may carry spaces, {@code +} and dashes: only its digits are kept.
 */
public record ReservationRequest(@NotNull Instant startsAt, @Min(1) @Max(1440)
                                 Integer durationMinutes, @NotNull @Min(1) @Max(500)
                                 Integer partySize, @NotBlank @Size(max = 120) String contactName,
                                 @Size(max = 40) String contactPhone, UUID customerId, @Size(max = 500) String notes,
                                 @Size(max = 20) List<@NotNull UUID> tableIds) {
}
