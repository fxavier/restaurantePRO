package com.xavier.backend.orders.model.event;

import com.xavier.backend.shared.Money;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A subtotal (a non-fiscal bill, §8.1) was asked for on {@code terminalId} (published by S14): production prints it on
 * the terminal's printer. It is not a document.
 *
 * @param requestId a UUIDv7 drawn once per subtotal command (D89 (2)): a deliberate second subtotal has another id, a
 *                  redelivered event the same. {@code null} in a publication stored by R2, which had no such field
 *                  (consumers then fall back to the content digest). It stays the last component.
 */
@NamedInterface("event")
public record SubtotalRequested(UUID orderId, UUID storeId, UUID terminalId, long orderNumber, String tableCode,
                                List<SubtotalLine> lines, Money due, UUID requestId) {

    /** The R2 shape, without a request id: what a redelivered R2 publication is. */
    public SubtotalRequested(UUID orderId, UUID storeId, UUID terminalId, long orderNumber, String tableCode,
                             List<SubtotalLine> lines, Money due) {
        this(orderId, storeId, terminalId, orderNumber, tableCode, lines, due, null);
    }
}
