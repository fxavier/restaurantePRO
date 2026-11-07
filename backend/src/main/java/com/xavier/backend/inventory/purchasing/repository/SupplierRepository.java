package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.Supplier;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Page<Supplier> findAllByOrderByCodeAsc(Pageable pageable);
}
