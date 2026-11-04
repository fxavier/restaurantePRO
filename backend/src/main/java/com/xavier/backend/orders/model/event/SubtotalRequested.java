package com.xavier.backend.orders.model.event;

import com.xavier.backend.shared.Money;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A subtotal (a non-fiscal bill, §8.1) was asked for on {@code terminalId} (published by S14): production prints it on
 * the terminal's printer. It is not a document.
 */
@NamedInterface("event")
public record SubtotalRequested(UUID orderId, UUID storeId, UUID terminalId, long orderNumber, String tableCode,
                                List<SubtotalLine> lines, Money due) {
}
