package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.QuickPage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuickPageRepository extends JpaRepository<QuickPage, UUID> {

    /** The pages a store sees: its own and those of every store. */
    @Query("SELECT p FROM QuickPage p WHERE p.storeId IS NULL OR p.storeId = :storeId ORDER BY p.position, p.code")
    List<QuickPage> findAllForStore(@Param("storeId") UUID storeId);

    List<QuickPage> findAllByStoreIdIsNullOrderByPositionAscCodeAsc();

    @Query("SELECT new com.xavier.backend.catalog.repository.EntryStore(p.storeId) FROM QuickPage p WHERE p.id = :id")
    Optional<EntryStore> findStoreById(@Param("id") UUID id);
}
