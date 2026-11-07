package com.xavier.backend.inventory.purchasing.model.dto;

import java.util.UUID;

public record SupplierView(UUID id, String code, String name, String taxId, String email, String phone, String notes,
                           boolean active, long version) {
}
