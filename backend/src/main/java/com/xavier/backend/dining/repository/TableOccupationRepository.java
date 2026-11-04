package com.xavier.backend.dining.repository;

import com.xavier.backend.dining.model.TableOccupation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TableOccupationRepository extends JpaRepository<TableOccupation, UUID> {

    Optional<TableOccupation> findByTableIdAndClosedAtIsNull(UUID tableId);

    Optional<TableOccupation> findByOrderIdAndClosedAtIsNull(UUID orderId);

    List<TableOccupation> findByStoreIdAndClosedAtIsNull(UUID storeId);

    boolean existsByTableIdAndClosedAtIsNull(UUID tableId);

    @Query("""
            select count(o) > 0 from TableOccupation o, RestaurantTable t
            where o.tableId = t.id and t.areaId = :areaId and o.closedAt is null""")
    boolean existsOpenInArea(UUID areaId);
}
