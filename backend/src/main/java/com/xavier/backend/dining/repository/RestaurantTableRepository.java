package com.xavier.backend.dining.repository;

import com.xavier.backend.dining.model.RestaurantTable;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, UUID> {

    Page<RestaurantTable> findByStoreId(UUID storeId, Pageable pageable);

    List<RestaurantTable> findByStoreIdAndActiveTrueOrderByCode(UUID storeId);

    boolean existsByStoreIdAndCode(UUID storeId, String code);

    boolean existsByStoreIdAndCodeAndIdNot(UUID storeId, String code, UUID id);

    /**
     * {@code SELECT ... FOR UPDATE}: serializes the writers of one table's occupation, lock and activity, so an
     * opening and a deactivation (or a lock) never both pass their checks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RestaurantTable t where t.id = :id")
    Optional<RestaurantTable> findForUpdateById(UUID id);

    /** Only the store of a table, to authorize before loading it (D42). */
    @Query("select t.storeId from RestaurantTable t where t.id = :id")
    Optional<UUID> findStoreIdById(UUID id);
}
