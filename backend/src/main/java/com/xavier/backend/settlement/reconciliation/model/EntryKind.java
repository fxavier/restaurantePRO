package com.xavier.backend.settlement.reconciliation.model;

/**
 * What reconciling one statement line, or one payment the statement lacks, found (D78).
 * <ul>
 *   <li>{@code MATCHED}: one payment, same amount (the provider's fee is recorded apart).</li>
 *   <li>{@code AMOUNT_MISMATCH}: one payment by its reference or authorization code, another amount.</li>
 *   <li>{@code MISSING_INTERNAL}: the provider has it and no single payment of ours is it.</li>
 *   <li>{@code MISSING_PROVIDER}: a payment of the period that the statement does not have.</li>
 *   <li>{@code DUPLICATE_LINE}: the line's one payment was already matched (by an earlier batch or line).</li>
 * </ul>
 */
public enum EntryKind {
    MATCHED,
    AMOUNT_MISMATCH,
    MISSING_INTERNAL,
    MISSING_PROVIDER,
    DUPLICATE_LINE
}
