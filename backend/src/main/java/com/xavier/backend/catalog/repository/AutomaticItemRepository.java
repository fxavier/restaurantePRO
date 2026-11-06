package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.AutomaticItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutomaticItemRepository extends JpaRepository<AutomaticItem, UUID> {

    /** The automatic items a store sees: its own and those of every store, ordered by id (creation order). */
    @Query("SELECT a FROM AutomaticItem a WHERE a.storeId IS NULL OR a.storeId = :storeId ORDER BY a.id")
    List<AutomaticItem> findAllForStore(@Param("storeId") UUID storeId);

    List<AutomaticItem> findAllByStoreIdIsNullOrderById();

    Optional<StoreScope> findScopeById(UUID id);
}
