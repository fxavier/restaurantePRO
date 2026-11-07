package com.xavier.backend.inventory.purchasing.repository;

import java.util.UUID;

/** The store and purchase order (or {@code null}) of a goods receipt. */
public record ReceiptRef(UUID storeId, UUID purchaseOrderId) {
}
