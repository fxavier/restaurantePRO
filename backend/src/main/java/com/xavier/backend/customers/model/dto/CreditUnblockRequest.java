package com.xavier.backend.customers.model.dto;

import jakarta.validation.constraints.Size;

/** Lifting a credit block; the reason is optional and goes to the audit trail. */
public record CreditUnblockRequest(@Size(max = 500) String reason) {
}
