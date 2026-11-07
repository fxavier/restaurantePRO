package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.orders.DeliverySummary;
import com.xavier.backend.shared.Money;
import java.util.UUID;

/**
 * The consumption and balance of one OPEN order ({@code balance = due − paid − credit}, D74), for the POS table map ({@code GET /pos/settlement/open-orders}),
 * with the table it sits at now ({@code null} for COUNTER, TAKEAWAY and DELIVERY orders; R2 backlog, D66). R4 (D129):
 * how it is served, its customer ({@code null} when none) and, for a DELIVERY order, its delivery (status, courier,
 * promise, zone), so the cashier sees what each courier still owes.
 */
public record OpenOrderBalance(UUID orderId, Money due, Money paid, Money balance, String financialState,
                               UUID tableId, String tableCode, Money credit, String mode,
                               UUID customerId, DeliverySummary delivery) {
}
