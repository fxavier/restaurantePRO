package com.xavier.backend.dining.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Replaces the details of a BOOKED reservation read at {@code version} (D17: a stale version is 409). */
public record ReservationUpdateRequest(@NotNull Long version, @NotNull Instant startsAt,
                                       @Min(1) @Max(1440) Integer durationMinutes,
                                       @NotNull @Min(1) @Max(500) Integer partySize,
                                       @NotBlank @Size(max = 120) String contactName,
                                       @Size(max = 40) String contactPhone, UUID customerId,
                                       @Size(max = 500) String notes, @Size(max = 20) List<@NotNull UUID> tableIds) {
}
