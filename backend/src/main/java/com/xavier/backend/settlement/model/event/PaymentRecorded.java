package com.xavier.backend.settlement.model.event;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** A payment was recorded (committed with it). Reporting reads its facts through {@code SettlementFacts} (D84). */
@NamedInterface("event")
public record PaymentRecorded(UUID paymentId, UUID storeId) {
}
