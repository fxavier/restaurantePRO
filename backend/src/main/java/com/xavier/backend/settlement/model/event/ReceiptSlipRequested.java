package com.xavier.backend.settlement.model.event;

import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A non-fiscal payment slip to print on {@code terminalId}'s printer (D75, published from S29 for debt payments):
 * production prints {@code lines} through its one print queue (source {@code RECEIPT_SLIP}).
 */
@NamedInterface("event")
public record ReceiptSlipRequested(UUID paymentId, UUID storeId, UUID terminalId, List<String> lines) {
}
