package com.xavier.backend.dining.repository;

import com.xavier.backend.dining.model.TableOccupation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableOccupationRepository extends JpaRepository<TableOccupation, UUID> {

    Optional<TableOccupation> findByTableIdAndClosedAtIsNull(UUID tableId);

    Optional<TableOccupation> findByOrderIdAndClosedAtIsNull(UUID orderId);

    List<TableOccupation> findByStoreIdAndClosedAtIsNull(UUID storeId);
}
