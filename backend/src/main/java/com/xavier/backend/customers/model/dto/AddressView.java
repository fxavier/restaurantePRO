package com.xavier.backend.customers.model.dto;

import java.util.UUID;

/** A customer address; {@code kind} is BILLING or DELIVERY, and {@code version} is sent back on update. */
public record AddressView(UUID id, String kind, String line1, String line2, String city, String postalCode,
                          String country, String notes, long version) {
}
