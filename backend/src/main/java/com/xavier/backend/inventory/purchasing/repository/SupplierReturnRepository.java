package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.SupplierReturn;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, UUID> {

    /** The store and receipt (or {@code null}) of a return alone, to authorize and lock in order (D42). */
    @Query("SELECT new com.xavier.backend.inventory.purchasing.repository.ReceiptRef(s.storeId, s.goodsReceiptId) "
            + "FROM SupplierReturn s WHERE s.id = :id")
    Optional<ReceiptRef> refOf(UUID id);

    Page<SupplierReturn> findByStoreIdOrderByCreatedAtDesc(UUID storeId, Pageable pageable);

    /** Σ base quantity of the other CONFIRMED returns of one receipt line. */
    @Query("""
            SELECT COALESCE(SUM(l.quantityBase), 0) FROM SupplierReturn s JOIN s.lines l
            WHERE l.receiptLineId = :receiptLineId AND s.id <> :returnId
              AND s.status = com.xavier.backend.inventory.purchasing.model.PurchaseDocumentStatus.CONFIRMED""")
    BigDecimal returnedByOthers(UUID receiptLineId, UUID returnId);
}
