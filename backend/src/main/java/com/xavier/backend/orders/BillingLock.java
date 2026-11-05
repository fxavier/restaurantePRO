package com.xavier.backend.orders;

/**
 * What may still change once settlement has started (D49, P13). {@code PAYMENT} from the first payment attempt:
 * voids and outgoing transfers are refused, new rounds are still allowed. {@code INVOICE} once the document draft
 * exists: every change is refused ({@code ORDER_FROZEN}).
 */
public enum BillingLock {
    NONE,
    PAYMENT,
    INVOICE
}
