package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashRegister;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashRegisterRepository extends JpaRepository<CashRegister, UUID> {

    Page<CashRegister> findByStoreId(UUID storeId, Pageable pageable);

    @Query("select x.storeId from CashRegister x where x.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);
}
