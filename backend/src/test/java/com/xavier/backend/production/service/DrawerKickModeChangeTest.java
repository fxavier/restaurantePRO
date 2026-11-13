package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.DrawerKicks;
import com.xavier.backend.production.DrawerKicks.Till;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D158 (amended 2026-11-13, S60 review): "no redirect" includes the mode change. A printer that leaves NORMAL fails
 * its QUEUED kicks at once ({@code PRINTER_NOT_NORMAL}), and a REDIRECT's move of undelivered jobs never takes a
 * kick, so another till's drawer never opens. Each kick waits behind a send in flight (a PROCESSING job), so no
 * worker claims it before the mode changes.
 */
@ProductionModuleTest
class DrawerKickModeChangeTest {

    @Autowired
    DrawerKicks kicks;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PrintWorker worker;

    @Test
    void aRedirectFailsTheQueuedKickAndNeverMovesItToAnotherDrawer() throws Exception {
        try (TcpFakePrinter mine = TcpFakePrinter.accepting(); TcpFakePrinter theirs = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), mine.host(), mine.port(), DrawerKicks.DRAWER);
            UUID other = fixtures.printer(till.store(), "O" + UUID.randomUUID().toString().substring(0, 6),
                    theirs.host(), theirs.port(), DrawerKicks.DRAWER);
            UUID sending = sendInFlight(till);
            UUID job = queuedKick(till);

            fixtures.changeMode(till.store(), till.printer(), "REDIRECT", other, Permission.PRODUCTION_MANAGE)
                    .andExpect(status().isOk());

            assertThat(fixtures.job(job)).containsEntry("status", "FAILED")
                    .containsEntry("last_error", "PRINTER_NOT_NORMAL").containsEntry("printer_id", till.printer());
            finish(sending);
            worker.poll();
            worker.poll();
            assertThat(theirs.received()).as("another till's drawer").isEmpty();
            assertThat(mine.received()).isEmpty();
        }
    }

    @Test
    void holdingAPrinterFailsItsQueuedKick() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);
        sendInFlight(till);
        UUID job = queuedKick(till);

        fixtures.mode(till.store(), till.printer(), "HOLD");

        assertThat(fixtures.job(job)).containsEntry("status", "FAILED")
                .containsEntry("last_error", "PRINTER_NOT_NORMAL");
    }

    /** A test page that the printer is sending now (PROCESSING): its next kick waits. */
    private UUID sendInFlight(Till till) throws Exception {
        fixtures.mode(till.store(), till.printer(), "HOLD");
        UUID page = fixtures.testPage(till.store(), till.printer());
        jdbc.sql("UPDATE production.print_job SET status = 'PROCESSING', attempts = 1, started_at = now() "
                + "WHERE id = ?").param(page).update();
        fixtures.mode(till.store(), till.printer(), "NORMAL");
        return page;
    }

    private UUID queuedKick(Till till) throws Exception {
        UUID payment = UUID.randomUUID();
        kicks.requested(till, "PAYMENT", payment);
        await(() -> kicks.handled(payment) == 1, "kick handled");
        UUID job = kicks.job(payment);
        assertThat(fixtures.jobStatus(job)).isEqualTo("QUEUED");
        return job;
    }

    private void finish(UUID job) {
        jdbc.sql("UPDATE production.print_job SET status = 'SENT', sent_at = now() WHERE id = ?").param(job)
                .update();
    }
}
