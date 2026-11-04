package com.xavier.backend.dining.repository;

import com.xavier.backend.dining.model.RestaurantTable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, UUID> {

    Page<RestaurantTable> findByStoreId(UUID storeId, Pageable pageable);

    List<RestaurantTable> findByStoreIdAndActiveTrueOrderByCode(UUID storeId);

    boolean existsByStoreIdAndCode(UUID storeId, String code);

    boolean existsByStoreIdAndCodeAndIdNot(UUID storeId, String code, UUID id);


    /** Only the store of a table, to authorize before loading it (D42). */
    @Query("select t.storeId from RestaurantTable t where t.id = :id")
    Optional<UUID> findStoreIdById(UUID id);
}
