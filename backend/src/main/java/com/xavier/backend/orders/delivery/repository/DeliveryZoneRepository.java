package com.xavier.backend.orders.delivery.repository;

import com.xavier.backend.orders.delivery.model.DeliveryZone;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, UUID> {

    /**
     * The table, for the D42 store probe ({@code shared.persistence.StoreProbe}): it sees every store, so another
     * store's row is found (403), not mistaken for absent (404) under row-level security.
     */
    String TABLE = "orders.delivery_zone";

    List<DeliveryZone> findByStoreIdOrderByCode(UUID storeId);

    List<DeliveryZone> findByStoreIdAndActiveTrueOrderByCode(UUID storeId);
}
