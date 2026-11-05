package com.xavier.backend.production.repository;

import com.xavier.backend.production.model.DestinationRoute;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DestinationRouteRepository extends JpaRepository<DestinationRoute, DestinationRoute.Key> {

    @Query("select r from DestinationRoute r where r.key.storeId = :storeId order by r.key.destinationCode")
    List<DestinationRoute> findByStore(@Param("storeId") UUID storeId);

    /** Runs at once (before the inserts of the replacement table). */
    @Modifying(flushAutomatically = true)
    @Query("delete from DestinationRoute r where r.key.storeId = :storeId")
    int deleteByStore(@Param("storeId") UUID storeId);
}
