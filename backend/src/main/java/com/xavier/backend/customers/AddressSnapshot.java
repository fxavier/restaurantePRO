package com.xavier.backend.customers;

import java.util.UUID;

/**
 * A customer address as another module copies it (D100: the delivery address of an order, which keeps the copy
 * however the customer's address changes later). {@code kind} is BILLING or DELIVERY.
 */
public record AddressSnapshot(UUID addressId, String kind, String line1, String line2, String city,
                              String postalCode, String country, String notes) {
}
