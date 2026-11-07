package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.ConsumptionRoute;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConsumptionRouteRepository extends JpaRepository<ConsumptionRoute, UUID> {

    List<ConsumptionRoute> findByStoreIdOrderByDestinationCode(UUID storeId);

    @Query("SELECT r.storeId FROM ConsumptionRoute r WHERE r.id = :id")
    Optional<UUID> storeOf(UUID id);
}
