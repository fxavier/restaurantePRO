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
    /** The approver of a reclassification or a void is the operator asking for it (D28, D62). */
    SELF_APPROVAL,
    /** R3 (D67): part of that line's quantity is already in a LIVE partition. */
    QUANTITY_ALREADY_PARTITIONED,
    /** R3 (D67, D71): more than the line's net (billable) quantity. */
    QUANTITY_EXCEEDS_NET,
    /** R3 (D67, D70): the order's document draft exists (billing lock INVOICE): its split is fixed. */
    ORDER_FROZEN,
    /** R3 (D67): the partition is more than the residual still unpaid (due − LIVE partitions − ORDER payments). */
    AMOUNT_EXCEEDS_RESIDUAL,
    /** R3 (D67): equal parts of a residual that cannot give every part a positive amount. */
    NOTHING_TO_SPLIT,
    /** R3 (D67): the partition has a payment, an open attempt or a document: it cannot be cancelled. */
    PARTITION_IN_USE,
    /** R3 (D70): only a LIVE ITEMS partition is invoiced on its own. */
    PARTITION_NOT_INVOICEABLE,
    /** R3 (D71): the quantity to void is in a LIVE partition: cancel the partition first. */
    QUANTITY_PARTITIONED,
    /** R3 (D71): the void would leave the bill below what was paid or put on account: refund first. */
    VOID_EXCEEDS_UNPAID,
    /** R3 (D71, P31): the quantity is on a document; correcting it needs the market's fiscal adapter. */
    CORRECTION_REQUIRES_FISCAL_ADAPTER,
    /**
     * R3 (D68 amendment): the command would leave a partition share or a line's remaining pool outside fiscal's
     * bound for allocated lines (after cancels or a void the rounding drifts): cancel or re-split differently.
     */
    SPLIT_ROUNDING_LIMIT,
    /**
     * R3 (D74): the target's sale document is ISSUED already, without this customer on account: it cannot become a
     * credit sale any more.
     */
    DOCUMENT_ALREADY_ISSUED,
    /** R3 (D74): the credit charge is posted, or its document is issued (the sweeper posts it): it is not cancelled. */
    CHARGE_POSTED,
    /** R3 (D75, P33): a debt payment is at most the customer's open debt (no advance), or the debits it names. */
    AMOUNT_EXCEEDS_DEBT,
    /** R3 (D75): a debit a debt payment names is unknown, of another customer, or already settled. */
    DEBIT_NOT_OPEN,
    /**
     * R3 (D81 (3)): an UNKNOWN attempt is reclassified as FAILED only once it is older than
     * {@code restaurantpro.settlement.reclassify-failed-min-age} and the provider answered a status query about it
     * since (a method whose provider is gone: older than {@code reclassify-failed-without-provider-min-age}).
     */
    RECLASSIFY_TOO_EARLY,
    /** R3 (D77): more than what is left of the payment once its earlier refunds are taken out. */
    REFUND_EXCEEDS_REFUNDABLE,
    /** R3 (D77): a refund to a method other than cash needs the terminal's or provider's refund reference. */
    EXTERNAL_REFERENCE_REQUIRED,
    /** R4 (D105): the discount would leave the bill below what was paid, put on account or is in flight. */
    DISCOUNT_EXCEEDS_UNPAID,
    /** R4 (D127): a DELIVERY order is closed, and its document issued at the close, only once it is DELIVERED. */
    DELIVERY_NOT_DELIVERED
}
