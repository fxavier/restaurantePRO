package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/pos/payment-attempts/{id}/confirm}: what the external terminal approved. Only its optional
 * authorization code is kept; no card data is sent, stored or logged.
 */
public record ConfirmAttemptRequest(@Size(max = 50) String authorizationCode) {
}
