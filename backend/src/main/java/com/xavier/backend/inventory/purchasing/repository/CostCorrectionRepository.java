package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.ReceiptCostCorrection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CostCorrectionRepository extends JpaRepository<ReceiptCostCorrection, UUID> {

    Optional<ReceiptCostCorrection> findFirstByReceiptLineIdOrderBySeqDesc(UUID receiptLineId);

    List<ReceiptCostCorrection> findByReceiptIdOrderByAtAscSeqAsc(UUID receiptId);
}
