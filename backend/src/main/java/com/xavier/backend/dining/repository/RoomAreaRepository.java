package com.xavier.backend.dining.repository;

import com.xavier.backend.dining.model.RoomArea;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomAreaRepository extends JpaRepository<RoomArea, UUID> {

    Page<RoomArea> findByStoreId(UUID storeId, Pageable pageable);

    List<RoomArea> findByStoreIdAndActiveTrueOrderByCode(UUID storeId);

    boolean existsByStoreIdAndCode(UUID storeId, String code);

    boolean existsByStoreIdAndCodeAndIdNot(UUID storeId, String code, UUID id);



    /**
     * The table, for the D42 store probe ({@code shared.persistence.StoreProbe}): it sees every store, so another
     * store's row is found (403), not mistaken for absent (404) under row-level security.
     */
    String TABLE = "dining.room_area";
}
