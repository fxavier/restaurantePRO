package com.xavier.backend.settlement.model.event;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** A credit charge was posted to the customer's ledger (published from S29). */
@NamedInterface("event")
public record CreditChargePosted(UUID chargeId, UUID storeId) {
}
