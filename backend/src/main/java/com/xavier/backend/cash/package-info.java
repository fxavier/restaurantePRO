/**
 * Cash ("caixa", spec §4 "Recebimentos, documentos e caixa", §8.3, §8.4; D34, P16, P19): cash registers, business
 * cash days, direct cash sessions with an opening float and a blind count, cash movements, and cash closings with
 * approval, plus the financial close and the reopen of a day.
 *
 * <p>Public API: this root package, {@link com.xavier.backend.cash.CashApi} and its records, for settlement, which
 * owns the coordinated closes (D36, D37). Cash never decides that money came in: a {@code PAYMENT_RECEIPT} movement
 * exists only because settlement called {@code recordReceipt} for a confirmed payment (spec §7.5, §7.7).
 * Allowed dependencies follow D35: organization through {@code OrganizationApi}, {@code BusinessDays} and its
 * {@code dto} named interface, identity only for {@code ApproverVerifier}. Never orders or settlement.
 */
@ApplicationModule(displayName = "Cash", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto", "identity"})
package com.xavier.backend.cash;

import org.springframework.modulith.ApplicationModule;
