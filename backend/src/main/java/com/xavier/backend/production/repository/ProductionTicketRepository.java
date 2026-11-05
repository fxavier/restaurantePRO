package com.xavier.backend.production.repository;

import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.model.TicketSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductionTicketRepository extends JpaRepository<ProductionTicket, UUID> {

    boolean existsBySourceTypeAndSourceIdAndDestination(TicketSource sourceType, UUID sourceId, String destination);

    @Query("select t.storeId from ProductionTicket t where t.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);
}
