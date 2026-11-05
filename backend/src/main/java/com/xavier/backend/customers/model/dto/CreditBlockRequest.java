package com.xavier.backend.customers.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the customer's credit is blocked. */
public record CreditBlockRequest(@NotBlank @Size(max = 500) String reason) {
}
