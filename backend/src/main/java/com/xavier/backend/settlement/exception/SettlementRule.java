package com.xavier.backend.settlement.exception;

/**
 * The stable {@code code} of a settlement 422 (PRD R2 conventions): clients switch on it, so a constant is never
 * renamed.
 */
public enum SettlementRule {
    /** The paying employee has no OPEN cash session in the store (P16). */
    NO_OPEN_CASH_SESSION,
    /** The amount is more than what is still due on the order (P12; §7.5 two terminals, one bill). */
    AMOUNT_EXCEEDS_BALANCE,
    /** The order is CLOSED or CANCELLED: nothing more is paid or billed on it. */
    ORDER_NOT_OPEN,
    /** No active payment method has that code. */
    UNKNOWN_PAYMENT_METHOD,
    /** An amount is zero, negative or in another currency than the order's. */
    INVALID_AMOUNT,
    /** Cash: the tendered amount is less than the amount paid. */
    TENDERED_BELOW_AMOUNT,
    /** A tendered amount (and so change) was sent for a method other than cash (P12). */
    CHANGE_FOR_CASH_ONLY,
    /** An INTEGRATED method names a provider for which no {@code PaymentProvider} bean exists (D53). */
    PROVIDER_NOT_AVAILABLE,
    /** A new method of kind CASH: only the seeded CASH method is cash. */
    INVALID_METHOD_KIND,
    /** The order cannot close: what was paid is not what is due (D50 §7.8). */
    NOT_SETTLED,
    /** The order has nothing billable left: cancel it instead (D55). */
    NOTHING_TO_BILL,
    /** The order's sale document is not ISSUED yet (D50 §7.6), or a store still has fiscal drafts (day close). */
    FISCAL_PENDING,
    /** The day cannot close operationally: an order of the day (or before) is still OPEN (§8.4). */
    OPEN_ORDERS,
    /** The day cannot close operationally: a cash session of the day is still OPEN (§8.4). */
    OPEN_SESSIONS,
    /** The day cannot close operationally: a payment attempt of the store is still in doubt (§7.4). */
    PAYMENT_IN_DOUBT,
    /** A manual reclassification needs a reason (D50 §7.4). */
    REASON_REQUIRED,
    /** A manual reclassification is to SUCCEEDED or FAILED only. */
    INVALID_OUTCOME,
    /** The approver of a reclassification is the operator asking for it (D28, D62). */
    SELF_APPROVAL
}
