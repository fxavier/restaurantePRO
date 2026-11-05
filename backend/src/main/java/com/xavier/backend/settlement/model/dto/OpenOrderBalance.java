package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.util.UUID;

/**
 * The consumption and balance of one OPEN order, for the POS table map ({@code GET /pos/settlement/open-orders}),
 * with the table it sits at now ({@code null} for COUNTER and TAKEAWAY orders; R2 backlog, D66).
 */
public record OpenOrderBalance(UUID orderId, Money due, Money paid, Money balance, String financialState,
                               UUID tableId, String tableCode) {
}
