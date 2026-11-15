package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashRegister;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashRegisterRepository extends JpaRepository<CashRegister, UUID> {

    Page<CashRegister> findByStoreId(UUID storeId, Pageable pageable);

    Page<CashRegister> findByStoreIdAndActiveTrue(UUID storeId, Pageable pageable);
}
