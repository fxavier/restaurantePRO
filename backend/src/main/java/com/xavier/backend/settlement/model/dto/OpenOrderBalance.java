package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.util.UUID;

/**
 * The consumption and balance of one OPEN order, for the POS table map ({@code GET /pos/settlement/open-orders}). The
 * POS joins it to the table through its order list (dining cannot see orders).
 */
public record OpenOrderBalance(UUID orderId, Money due, Money paid, Money balance, String financialState) {
}
