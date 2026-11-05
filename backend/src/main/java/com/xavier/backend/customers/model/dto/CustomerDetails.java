package com.xavier.backend.customers.model.dto;

import java.util.List;
import java.util.UUID;

/** A customer with contacts; {@code version} is sent back on update (D17). */
public record CustomerDetails(UUID id, String name, String taxId, String email, String notes, boolean active,
                              long version, List<PhoneView> phones, List<AddressView> addresses) {
}
