package com.xavier.backend.organization.repository;

import com.xavier.backend.organization.model.Store;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);
}
