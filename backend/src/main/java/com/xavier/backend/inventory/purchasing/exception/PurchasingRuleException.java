package com.xavier.backend.inventory.purchasing.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** A purchasing business rule refused the request: 422 with the stable {@code code} (PRD R4 conventions). */
public class PurchasingRuleException extends BusinessRuleException {

    private final PurchasingRule rule;

    public PurchasingRuleException(PurchasingRule rule, String detail) {
        this(rule, detail, Map.of());
    }

    public PurchasingRuleException(PurchasingRule rule, String detail, Map<String, ?> properties) {
        super(rule.name(), detail, properties);
        this.rule = rule;
    }

    /** A purchase order with a confirmed receipt is closed, never cancelled (D124). */
    public static PurchasingRuleException hasReceipts(UUID purchaseOrderId) {
        return new PurchasingRuleException(PurchasingRule.PO_HAS_RECEIPTS, "Purchase order " + purchaseOrderId
                + " has confirmed receipts: close it instead of cancelling it.");
    }

    /** Goods are received only against a SUBMITTED purchase order. */
    public static PurchasingRuleException notSubmitted(UUID purchaseOrderId, Object status) {
        return new PurchasingRuleException(PurchasingRule.PO_NOT_SUBMITTED, "Purchase order " + purchaseOrderId
                + " is " + status + ": goods are received only against a submitted order.");
    }

    /** {@code available} is what the receipt line received less what was already returned of it. */
    public static PurchasingRuleException returnExceedsReceived(UUID receiptLineId, BigDecimal available) {
        return new PurchasingRuleException(PurchasingRule.RETURN_EXCEEDS_RECEIVED, "Only "
                + available.toPlainString() + " of receipt line " + receiptLineId + " can still be returned.",
                Map.of("receiptLineId", receiptLineId, "available", available));
    }

    public static PurchasingRuleException receiptNotConfirmed(UUID receiptId, Object status) {
        return new PurchasingRuleException(PurchasingRule.RECEIPT_NOT_CONFIRMED, "Goods receipt " + receiptId
                + " is " + status + ": only a confirmed receipt has costs to correct or goods to return.");
    }

    /**
     * A document names something that does not belong with it: an order line of another order or stock item, a
     * receipt line of another receipt, a pack of another stock item, another supplier or warehouse than its source.
     */
    public static PurchasingRuleException referenceMismatch(String detail) {
        return new PurchasingRuleException(PurchasingRule.PURCHASE_REFERENCE_MISMATCH, detail);
    }

    /** No cost was given and none can be defaulted (an order line, a supplier's last cost; D123). */
    public static PurchasingRuleException unitCostRequired(UUID stockItemId) {
        return new PurchasingRuleException(PurchasingRule.UNIT_COST_REQUIRED, "Stock item " + stockItemId
                + " needs a unit cost: this supplier has no last cost for it.");
    }

    public static PurchasingRuleException reasonRequired(String what) {
        return new PurchasingRuleException(PurchasingRule.REASON_REQUIRED, "A " + what + " needs a reason.");
    }

    /** A line's value would not fit a stock value (15 integer digits, scale 4). */
    public static PurchasingRuleException lineValueTooLarge(UUID stockItemId) {
        return new PurchasingRuleException(PurchasingRule.LINE_VALUE_TOO_LARGE, "The line of stock item "
                + stockItemId + " is worth more than a stock value can hold: split it or check its cost.");
    }

    public PurchasingRule rule() {
        return rule;
    }
}
