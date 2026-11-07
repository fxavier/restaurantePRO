package com.xavier.backend.inventory.purchasing.repository;

import java.util.UUID;

/**
 * The store of a purchasing document and the document it is against ({@code null} for none): a receipt's purchase
 * order, a return's goods receipt. Read alone, to authorize and lock in order before the full load (D42).
 */
public record ReceiptRef(UUID storeId, UUID sourceId) {
}
