/**
 * Settlement ("recebimentos", spec §4 "Recebimentos, documentos e caixa", §7.3–§7.8, §8.3, §8.4; D34, D37, D48–D53,
 * D55, D63, P10, P12, P13): payment methods, payment attempts (cash, operator-confirmed external terminal, integrated
 * provider port with UNKNOWN and resolve), confirmed payments with their allocation and cash receipt exactly once,
 * the derived financial state of an order, and every coordinated close: issuing the order's sale document, closing
 * the order, closing a cash session and closing a cash day operationally.
 *
 * <p>Public API: this root package ({@link com.xavier.backend.settlement.PaymentProvider} SPI and its records).
 * Allowed dependencies follow D35: organization, identity (only {@code ApproverVerifier}), and the facades
 * {@code OrdersApi}, {@code CashApi} and {@code FiscalApi} (with fiscal's {@code dto} named interface, which its
 * facade takes and returns). Settlement never reads another module's tables.
 */
@ApplicationModule(displayName = "Settlement", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto", "identity", "orders", "cash", "fiscal",
        "fiscal :: dto"})
package com.xavier.backend.settlement;

import org.springframework.modulith.ApplicationModule;
