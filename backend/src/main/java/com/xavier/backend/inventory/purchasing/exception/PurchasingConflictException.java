package com.xavier.backend.inventory.purchasing.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** A purchasing state conflict: 409 with a stable {@code code} (PRD R4 conventions). */
public class PurchasingConflictException extends ConflictException {

    public PurchasingConflictException(String code, String message) {
        super(code, message);
    }

    /** A purchase order command its state does not allow (D124). */
    public static PurchasingConflictException orderState(UUID purchaseOrderId, Object status, String action) {
        return new PurchasingConflictException("PO_STATE",
                "Purchase order " + purchaseOrderId + " is " + status + " and cannot " + action + ".");
    }

    /** A goods receipt command its state does not allow (D125): a receipt is confirmed once. */
    public static PurchasingConflictException receiptState(UUID receiptId, Object status, String action) {
        return new PurchasingConflictException("RECEIPT_STATE",
                "Goods receipt " + receiptId + " is " + status + " and cannot " + action + ".");
    }

    public static PurchasingConflictException returnState(UUID returnId, Object status, String action) {
        return new PurchasingConflictException("RETURN_STATE",
                "Supplier return " + returnId + " is " + status + " and cannot " + action + ".");
    }

    public static PurchasingConflictException orderNumberTaken(UUID storeId, String number) {
        return new PurchasingConflictException("PO_NUMBER_TAKEN",
                "Store " + storeId + " already has a purchase order " + number + ".");
    }

    public static PurchasingConflictException supplierItemExists(UUID supplierId, UUID stockItemId) {
        return new PurchasingConflictException("SUPPLIER_ITEM_EXISTS", "Supplier " + supplierId
                + " already has stock item " + stockItemId + " in that pack.");
    }

    /** Two cost corrections of one receipt line at the same time: the later one retries on the new cost. */
    public static PurchasingConflictException correctionConcurrent(UUID receiptLineId) {
        return new PurchasingConflictException("COST_CORRECTION_CONCURRENT", "Receipt line " + receiptLineId
                + " was corrected at the same time; retry on its new cost.");
    }
}
