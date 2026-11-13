package com.xavier.backend.production;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import com.xavier.backend.settlement.model.event.CashDrawerKickRequested;

/**
 * Drawer-kick helpers (D158): a store whose terminal prints on a printer with a cash drawer, the kick event as
 * settlement publishes it, and reads of the kick's ticket, job and listener deliveries.
 */
@TestComponent
public class DrawerKicks {

    /** The profile fields of a printer with a drawer on pin 2 and a 100 ms pulse. */
    public static final String DRAWER = "\"drawerPin\": 0, \"drawerPulseMs\": 100";
    /** {@code ESC p 0 50 250}: what that drawer gets. */
    public static final String KICK_BYTES = "1b700032fa";

    /** A store's terminal and the printer it prints on. */
    public record Till(UUID store, UUID terminal, UUID printer) {
    }

    private final ProductionFixtures fixtures;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;

    DrawerKicks(ProductionFixtures fixtures, ApplicationEventPublisher events, TransactionTemplate tx,
                JdbcClient jdbc) {
        this.fixtures = fixtures;
        this.events = events;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    /** A new terminal of {@code store} printing on a new printer at {@code host:port} with {@code profileFields}. */
    public Till till(UUID store, String host, int port, String profileFields) throws Exception {
        UUID terminal = UUID.randomUUID();
        UUID printer = fixtures.printer(store, "K" + UUID.randomUUID().toString().substring(0, 6), host, port,
                profileFields);
        fixtures.terminalPrinters(store, Map.of(terminal, printer)).andExpect(status().isOk());
        return new Till(store, terminal, printer);
    }

    /** Settlement's event for a cash {@code sourceType} on the till, published and committed. */
    public void requested(Till till, String sourceType, UUID sourceId) {
        tx.executeWithoutResult(status -> events.publishEvent(new CashDrawerKickRequested(sourceType, sourceId,
                till.store(), till.terminal())));
    }

    /** Deliveries of the kick event about {@code sourceId} that the drawer-kick listener completed. */
    public long handled(UUID sourceId) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication WHERE serialized_event LIKE :id
                          AND listener_id LIKE '%DrawerKickListener%' AND completion_date IS NOT NULL""")
                .param("id", "%" + sourceId + "%").query(Long.class).single();
    }

    public List<Map<String, Object>> tickets(UUID sourceId) {
        return fixtures.tickets(sourceId);
    }

    public List<Map<String, Object>> jobs(UUID sourceId) {
        return fixtures.jobsOfSource(sourceId);
    }

    /** The one job of the kick about {@code sourceId}. */
    public UUID job(UUID sourceId) {
        return (UUID) jobs(sourceId).getFirst().get("id");
    }
}
