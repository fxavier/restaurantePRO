package com.xavier.backend.production.repository;

import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.model.TicketRouting;
import com.xavier.backend.production.model.TicketSource;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductionTicketRepository extends JpaRepository<ProductionTicket, UUID> {

    Page<ProductionTicket> findByStoreId(UUID storeId, Pageable pageable);

    Page<ProductionTicket> findByStoreIdAndRouting(UUID storeId, TicketRouting routing, Pageable pageable);

    boolean existsBySourceTypeAndSourceIdAndDestination(TicketSource sourceType, UUID sourceId, String destination);

    /**
     * The table, for the D42 store probe ({@code shared.persistence.StoreProbe}): it sees every store, so another
     * store's row is found (403), not mistaken for absent (404) under row-level security.
     */
    String TABLE = "production.production_ticket";

    /**
     * D89 (1): the compare-and-set UNROUTED -> ROUTED, the one change a ticket takes (the table's trigger allows no
     * other). 0 when the ticket is ROUTED already; a concurrent route waits on the row lock and then gets 0.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE production.production_ticket SET routing = 'ROUTED', printer_id = :printerId
            WHERE id = :id AND routing = 'UNROUTED'""", nativeQuery = true)
    int route(@Param("id") UUID id, @Param("printerId") UUID printerId);
}
