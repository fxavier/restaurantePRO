package com.xavier.backend.settlement.model.event;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** A refund was recorded (published from S30). Reporting reads its facts through {@code SettlementFacts}. */
@NamedInterface("event")
public record RefundRecorded(UUID refundId, UUID storeId) {
}
