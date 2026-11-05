package com.xavier.backend.settlement.model.dto;

import java.util.UUID;

/** A payment method (D52). {@code kind} is {@code CASH}, {@code EXTERNAL_TERMINAL} or {@code INTEGRATED}. */
public record PaymentMethodView(UUID id, String code, String name, String kind, String providerCode, boolean active,
                                long version) {
}
