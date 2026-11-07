package com.xavier.backend.orders.delivery.repository;

import com.xavier.backend.orders.delivery.model.DeliveryZone;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, UUID> {

    @Query("select z.storeId from DeliveryZone z where z.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);

    List<DeliveryZone> findByStoreIdOrderByCode(UUID storeId);

    List<DeliveryZone> findByStoreIdAndActiveTrueOrderByCode(UUID storeId);
}
