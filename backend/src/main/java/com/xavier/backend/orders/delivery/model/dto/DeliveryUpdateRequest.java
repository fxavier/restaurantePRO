package com.xavier.backend.orders.delivery.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** Changes a PENDING delivery (D127); what is null stays as it is. */
public record DeliveryUpdateRequest(@NotNull Long version, @Valid AddressRequest address, UUID zoneId,
                                    Instant promisedAt) {
}
