package com.xavier.backend.customers;

import com.xavier.backend.customers.model.dto.AddressView;
import java.util.UUID;

/**
 * What a document needs of its buyer, read once when the sale is made (§8.2: an issued document never reads the
 * live customer again). {@code billingAddress} is the customer's first BILLING address, or null.
 */
public record CustomerSnapshot(UUID customerId, String name, String taxId, AddressView billingAddress) {
}
