package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.util.List;
import java.util.UUID;

/**
 * An order as settlement sees it ({@code GET /pos/orders/{id}/settlement}, and the close's response).
 * {@code lifecycle} is {@code OPEN}, {@code CLOSED} or {@code CANCELLED}; {@code orderVersion} is the one the close
 * expects; {@code paid} is Σ of every payment of the order (any target) and {@code balance = due − paid};
 * {@code financialState} is {@code UNSETTLED}, {@code PARTIALLY_SETTLED} or {@code SETTLED} (derived, never stored,
 * §8.3); {@code fiscalState} is {@code NONE}, {@code DRAFT} or {@code ISSUED}, with its {@code documentId}, for the
 * order's own (residual) document; {@code openAttempts} are the attempts still CREATED, PENDING or UNKNOWN on any
 * target. R3 (D67–D70): {@code partitions} (LIVE and CANCELLED), {@code coverage} per billable line, and
 * {@code residualBalance}, what the ORDER target still has to collect; {@code taxRoundingDifference} per rate of the
 * order's lines, zero unless a settled void emptied a partitioned line's pool (D68 amendment), with where that cent
 * went (D96: absorbed, pendingCorrection, corrected, unassigned), and {@code taxRoundingAdjustments} the rows. R3 (D74): {@code credit}
 * is Σ of the live (RESERVED and POSTED) credit charges, never part of {@code paid}; {@code balance = due − paid −
 * credit} and the financial state counts both; {@code creditCharges} are every charge of the order, oldest first.
 * R3 (D77): {@code paid} is net of refunds, and {@code refunds} are every refund of the order's payments, oldest
 * first. R3 (D97): {@code paymentStatus} is live, {@code PaymentStatus.of(due, paid, credit)} over every target
 * ({@code UNPAID}, {@code PARTIALLY_PAID}, {@code PAID}, {@code ON_ACCOUNT} or {@code MIXED}); a document keeps the
 * one of its issue.
 */
public record OrderSettlementView(UUID orderId, UUID storeId, String lifecycle, long orderVersion, Money due,
                                  Money paid, Money balance, String financialState, String fiscalState,
                                  UUID documentId, List<AttemptView> openAttempts, List<PartitionView> partitions,
                                  List<LineCoverage> coverage, Money residualBalance,
                                  List<TaxRoundingDifference> taxRoundingDifference, Money credit,
                                  List<CreditChargeView> creditCharges, List<RefundView> refunds,
                                  List<TaxRoundingAdjustmentView> taxRoundingAdjustments, String paymentStatus) {
}
