package com.xavier.backend.orders.delivery.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * The delivery of a new DELIVERY order (D127): its customer, one of the customer's addresses ({@code addressId}) or a
 * typed one ({@code address}), never both, the zone, and optionally the promised time and notes for the courier
 * ({@code notes} replaces the address's own notes).
 */
public record DeliveryRequest(UUID customerId, UUID addressId, @Valid AddressRequest address, @NotNull UUID zoneId,
                              Instant promisedAt, @Size(max = AddressRequest.MAX_NOTES) String notes) {

    @AssertTrue(message = "give exactly one of addressId and address")
    public boolean isOneAddress() {
        return (addressId == null) != (address == null);
    }
}
