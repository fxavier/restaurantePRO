package com.xavier.backend.dining.repository;

import com.xavier.backend.dining.model.RoomArea;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoomAreaRepository extends JpaRepository<RoomArea, UUID> {

    Page<RoomArea> findByStoreId(UUID storeId, Pageable pageable);

    List<RoomArea> findByStoreIdAndActiveTrueOrderByCode(UUID storeId);

    boolean existsByStoreIdAndCode(UUID storeId, String code);

    boolean existsByStoreIdAndCodeAndIdNot(UUID storeId, String code, UUID id);



    /** Only the store of an area, to authorize before loading it (D42). */
    @Query("select a.storeId from RoomArea a where a.id = :id")
    Optional<UUID> findStoreIdById(UUID id);
}
