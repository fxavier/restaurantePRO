/**
 * Settlement ("recebimentos", spec §4 "Recebimentos, documentos e caixa", §7.3–§7.8, §8.3, §8.4; D34, D37, D48–D53,
 * D55, D63, P10, P12, P13): payment methods, payment attempts (cash, operator-confirmed external terminal, integrated
 * provider port with UNKNOWN and resolve), confirmed payments with their allocation and cash receipt exactly once,
 * the derived financial state of an order, and every coordinated close: issuing the order's sale document, closing
 * the order, closing a cash session and closing a cash day operationally.
 *
 * <p>R3 (D67–D71): bill partitions by items or amount and equal parts, payments per target (order residual or
 * partition), a document per item partition plus the residual document at close, and the coordinated void within
 * headroom; the split rule is {@link com.xavier.backend.settlement.PartitionAmounts} (D68 amended).
 *
 * <p>Public API: this root package ({@link com.xavier.backend.settlement.PaymentProvider} SPI and its records,
 * {@link com.xavier.backend.settlement.PartitionAmounts}, and {@link com.xavier.backend.settlement.SettlementFacts}
 * for reporting) and the {@code event} named interface ({@code model.event}: PaymentRecorded, RefundRecorded,
 * CreditChargePosted, ReceiptSlipRequested).
 * Allowed dependencies follow D35: organization, identity (only {@code ApproverVerifier}), and the facades
 * {@code OrdersApi}, {@code CashApi} and {@code FiscalApi} (with fiscal's {@code dto} named interface, which its
 * facade takes and returns). Settlement never reads another module's tables.
 */
@ApplicationModule(displayName = "Settlement", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto", "identity", "orders", "cash", "fiscal",
        "fiscal :: dto"})
package com.xavier.backend.settlement;

import org.springframework.modulith.ApplicationModule;
