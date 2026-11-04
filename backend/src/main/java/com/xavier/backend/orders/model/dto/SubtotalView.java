package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.model.event.SubtotalLine;
import com.xavier.backend.shared.Money;
import java.util.List;
import java.util.UUID;

/** The subtotal handed to production for printing (not a document, §8.1): the billable lines and the due. */
public record SubtotalView(UUID orderId, long orderNumber, String tableCode, List<SubtotalLine> lines, Money due) {
}
