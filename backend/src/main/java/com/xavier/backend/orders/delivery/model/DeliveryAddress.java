package com.xavier.backend.orders.delivery.model;

import java.util.Objects;

/** The address a delivery goes to, copied when the order opens (D127): later changes to the customer's never reach it. */
public record DeliveryAddress(String line1, String line2, String city, String postalCode, String notes) {

    public DeliveryAddress {
        Objects.requireNonNull(line1, "line1");
        if (line1.isBlank()) {
            throw new IllegalArgumentException("line1 must not be blank");
        }
    }
}
