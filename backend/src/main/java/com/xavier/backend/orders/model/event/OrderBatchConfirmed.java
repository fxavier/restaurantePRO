package com.xavier.backend.orders.model.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A round was confirmed (D50 §7.1): {@code lines} is the whole content of the round and only that, so a second round
 * prints only its new lines (A4). Its publication commits with the round and is the persisted production work;
 * production's listener is idempotent per {@code batchId} and then calls {@code OrdersProductionApi.markBatchDispatched}.
 *
 * @param tableCode the code of the order's table for DINE_IN orders, {@code null} for COUNTER and TAKEAWAY
 */
@NamedInterface("event")
public record OrderBatchConfirmed(UUID orderId, UUID batchId, int batchNo, UUID storeId, UUID terminalId,
                                  long orderNumber, String tableCode, UUID employeeId, Instant confirmedAt,
                                  List<Line> lines) {
}
