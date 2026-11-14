package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.ConsumptionRoute;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumptionRouteRepository extends JpaRepository<ConsumptionRoute, UUID> {

    List<ConsumptionRoute> findByStoreIdOrderByDestinationCode(UUID storeId);
}
