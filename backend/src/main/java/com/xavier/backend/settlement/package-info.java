/**
 * Settlement ("recebimentos", spec §4 "Recebimentos, documentos e caixa", §7.3–§7.8, §8.3, §8.4; D34, D37, D48–D53,
 * D55, D63, P10, P12, P13): payment methods, payment attempts (cash, operator-confirmed external terminal, integrated
 * provider port with UNKNOWN and resolve), confirmed payments with their allocation and cash receipt exactly once,
 * the derived financial state of an order, and every coordinated close: issuing the order's sale document, closing
 * the order, closing a cash session and closing a cash day operationally.
 *
 * <p>R3 (D67–D71): bill partitions by items or amount and equal parts, payments per target (order residual or
 * partition), a document per item partition plus the residual document at close, and the coordinated void within
 * headroom; the split rule is {@link com.xavier.backend.settlement.PartitionAmounts} (D68 amended). Credit (D74–D76):
 * credit sales (a {@code CreditCharge} reserved, its document issued on account, its debit posted through
 * {@code CustomersApi}; never a payment nor a cash movement) and debt payments to a customer's credit account. Refunds
 * (D77, P30): a {@code Refund} of a payment, to its own method, in the refunder's cash session, with an approver;
 * paid amounts are net of refunds. Payments in doubt (D81): the {@code InDoubtResolver} polls UNKNOWN attempts with
 * backoff, and a manual reclassification to FAILED is guarded by age. A manager's forced close of an abandoned cash
 * session (P22).
 *
 * <p>Public API: this root package ({@link com.xavier.backend.settlement.PaymentProvider} SPI and its records,
 * {@link com.xavier.backend.settlement.PartitionAmounts}, and {@link com.xavier.backend.settlement.SettlementFacts}
 * for reporting) and the {@code event} named interface ({@code model.event}: PaymentRecorded, RefundRecorded,
 * CreditChargePosted, ReceiptSlipRequested).
 * Allowed dependencies follow D35: organization, identity (only {@code ApproverVerifier}), and the facades
 * {@code OrdersApi}, {@code CashApi}, {@code FiscalApi} (with fiscal's {@code dto} named interface, which its
 * facade takes and returns) and, D65, {@code CustomersApi} (with customers' {@code dto}). Settlement never reads
 * another module's tables.
 */
@ApplicationModule(displayName = "Settlement", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto", "identity", "orders", "cash", "fiscal",
        "fiscal :: dto", "customers", "customers :: dto"})
package com.xavier.backend.settlement;

import org.springframework.modulith.ApplicationModule;
