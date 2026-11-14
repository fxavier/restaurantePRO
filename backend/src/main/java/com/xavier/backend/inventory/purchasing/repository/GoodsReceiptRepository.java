package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.GoodsReceipt;
import com.xavier.backend.inventory.purchasing.model.PurchaseDocumentStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    /** The store and order of a receipt alone, read once its store is authorized, to lock in order (D42). */
    @Query("SELECT new com.xavier.backend.inventory.purchasing.repository.ReceiptRef(r.storeId, r.purchaseOrderId) "
            + "FROM GoodsReceipt r WHERE r.id = :id")
    Optional<ReceiptRef> refOf(UUID id);

    Page<GoodsReceipt> findByStoreIdOrderByCreatedAtDesc(UUID storeId, Pageable pageable);

    boolean existsByPurchaseOrderIdAndStatus(UUID purchaseOrderId, PurchaseDocumentStatus status);

    /** Σ base quantity of the CONFIRMED receipt lines of an order, per order line: what arrived (D124). */
    @Query("""
            SELECT new com.xavier.backend.inventory.purchasing.repository.Received(l.purchaseOrderLineId,
                SUM(l.quantityBase))
            FROM GoodsReceipt r JOIN r.lines l
            WHERE r.purchaseOrderId = :purchaseOrderId AND l.purchaseOrderLineId IS NOT NULL
              AND r.status = com.xavier.backend.inventory.purchasing.model.PurchaseDocumentStatus.CONFIRMED
            GROUP BY l.purchaseOrderLineId""")
    List<Received> receivedOf(UUID purchaseOrderId);

    /** Σ base quantity of the CONFIRMED returns of one receipt line. */
    @Query("""
            SELECT COALESCE(SUM(l.quantityBase), 0) FROM SupplierReturn s JOIN s.lines l
            WHERE l.receiptLineId = :receiptLineId
              AND s.status = com.xavier.backend.inventory.purchasing.model.PurchaseDocumentStatus.CONFIRMED""")
    BigDecimal returnedOf(UUID receiptLineId);
}
