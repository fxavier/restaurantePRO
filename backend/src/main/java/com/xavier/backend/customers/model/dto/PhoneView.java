package com.xavier.backend.customers.model.dto;

import java.util.UUID;

/** A customer phone as its digits. */
public record PhoneView(UUID id, String digits, String label, boolean primary) {
}
