package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.StockTransfer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockTransferRepository extends JpaRepository<StockTransfer, UUID> {
}
