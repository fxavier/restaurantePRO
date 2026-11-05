package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.util.List;
import java.util.UUID;

/**
 * An order as settlement sees it ({@code GET /pos/orders/{id}/settlement}, and the close's response).
 * {@code lifecycle} is {@code OPEN}, {@code CLOSED} or {@code CANCELLED}; {@code orderVersion} is the one the close
 * expects; {@code financialState} is {@code UNSETTLED}, {@code PARTIALLY_SETTLED} or {@code SETTLED} (derived, never
 * stored, §8.3); {@code fiscalState} is {@code NONE}, {@code DRAFT} or {@code ISSUED}, with its {@code documentId};
 * {@code openAttempts} are the attempts still CREATED, PENDING or UNKNOWN.
 */
public record OrderSettlementView(UUID orderId, UUID storeId, String lifecycle, long orderVersion, Money due,
                                  Money paid, Money balance, String financialState, String fiscalState,
                                  UUID documentId, List<AttemptView> openAttempts) {
}
