package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V7008: a ticket's format is a fact like its content. Routing an UNROUTED ticket (D89 (1)) is the one update the
 * table allows, and it may set only routing and printer_id; changing content_format with it is refused.
 */
@ProductionModuleTest
class TicketContentFormatTriggerTest {

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    JdbcClient jdbc;

    @Test
    void routingATicketCannotChangeItsContentFormat() throws Exception {
        UUID store = stores.newStore();
        UUID printer = fixtures.printer(store, "T" + UUID.randomUUID().toString().substring(0, 6), "10.0.0.5", 9100);
        UUID ticket = unroutedTicket(store);

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE production.production_ticket SET routing = 'ROUTED', printer_id = ?, content_format = 'STYLED_V1'
                WHERE id = ?""").param(printer).param(ticket).update()).hasMessageContaining("append-only");

        jdbc.sql("UPDATE production.production_ticket SET routing = 'ROUTED', printer_id = ? WHERE id = ?")
                .param(printer).param(ticket).update();
        assertThat(fixtures.tickets(sourceOf(ticket))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("routing", "ROUTED")
                        .containsEntry("content_format", "PLAIN"));
    }

    private UUID unroutedTicket(UUID store) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination, kind,
                    printer_id, suppressed, content, created_at, routing, content_format)
                VALUES (?, ?, 'BATCH', ?, 'BAR', 'ORIGINAL', NULL, false, 'BAR', now(), 'UNROUTED', 'PLAIN')""")
                .param(id).param(store).param(UUID.randomUUID()).update();
        return id;
    }

    private UUID sourceOf(UUID ticket) {
        return jdbc.sql("SELECT source_id FROM production.production_ticket WHERE id = ?").param(ticket)
                .query(UUID.class).single();
    }
}
