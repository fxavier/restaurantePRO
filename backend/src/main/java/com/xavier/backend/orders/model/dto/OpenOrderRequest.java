package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.delivery.model.dto.DeliveryRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * A COUNTER, TAKEAWAY or (R4, D127) DELIVERY order (P15); {@code DINE_IN} is opened through its table (422).
 * {@code delivery} is given for a DELIVERY order only (without it: 422 {@code CUSTOMER_REQUIRED}).
 */
public record OpenOrderRequest(@NotNull OrderMode mode, @Positive @Max(999) Integer guests,
                               @Valid DeliveryRequest delivery) {

    @AssertTrue(message = "delivery is given only for a DELIVERY order")
    public boolean isDeliveryOnlyForDelivery() {
        return delivery == null || mode == OrderMode.DELIVERY;
    }
}
