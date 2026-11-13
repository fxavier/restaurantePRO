package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.DrawerKicks;
import com.xavier.backend.production.DrawerKicks.Till;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D158: a kick is never held, redirected or queued behind other work. On a printer that is not NORMAL (HOLD,
 * REDIRECT, IGNORE) or not active it is FAILED at once with {@code PRINTER_NOT_NORMAL}, unsent, and stays on its
 * printer; on a NORMAL printer whose head job waits to retry it goes out anyway, ahead of that backlog.
 */
@ProductionModuleTest
class DrawerKickHoldTest {

    @Autowired
    DrawerKicks kicks;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aKickOnAHeldPrinterFailsAtOnceAndIsNeverHeld() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);
            fixtures.mode(till.store(), till.printer(), "HOLD");

            UUID job = kicked(till);

            assertThat(fixtures.job(job)).containsEntry("last_error", "PRINTER_NOT_NORMAL")
                    .containsEntry("attempts", 0).containsEntry("printer_id", till.printer());
            assertThat(fake.received()).isEmpty();
        }
    }

    @Test
    void aKickOnARedirectingPrinterFailsAtOnceAndIsNotMoved() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);
            UUID other = fixtures.printer(till.store(), "O" + UUID.randomUUID().toString().substring(0, 6),
                    fake.host(), fake.port(), DrawerKicks.DRAWER);
            fixtures.changeMode(till.store(), till.printer(), "REDIRECT", other, Permission.PRODUCTION_MANAGE)
                    .andExpect(status().isOk());

            UUID job = kicked(till);

            assertThat(fixtures.job(job)).containsEntry("last_error", "PRINTER_NOT_NORMAL")
                    .containsEntry("printer_id", till.printer());
            assertThat(fake.received()).isEmpty();
        }
    }

    @Test
    void aKickOnAnIgnoringPrinterFailsAtOnceRatherThanBeingSuppressed() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);
        fixtures.mode(till.store(), till.printer(), "IGNORE");

        UUID job = kicked(till);

        assertThat(fixtures.job(job)).containsEntry("last_error", "PRINTER_NOT_NORMAL");
    }

    @Test
    void aKickOnAnInactivePrinterFailsAtOnce() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);
            jdbc.sql("UPDATE production.printer SET active = false WHERE id = ?").param(till.printer()).update();

            UUID job = kicked(till);

            assertThat(fixtures.job(job)).containsEntry("last_error", "PRINTER_NOT_NORMAL");
            assertThat(fake.received()).isEmpty();
        }
    }

    @Test
    void aKickGoesOutAheadOfAJobWaitingToRetry() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);
            fixtures.mode(till.store(), till.printer(), "HOLD");
            UUID waiting = fixtures.testPage(till.store(), till.printer());
            jdbc.sql("""
                    UPDATE production.print_job SET status = 'RETRY_WAIT', attempts = 1, started_at = now(),
                        last_error = 'NOT_CONNECTED', next_attempt_at = now() + interval '1 hour' WHERE id = ?""")
                    .param(waiting).update();
            fixtures.mode(till.store(), till.printer(), "NORMAL");
            UUID payment = UUID.randomUUID();

            kicks.requested(till, "PAYMENT", payment);
            await(() -> kicks.handled(payment) == 1, "kick handled");
            UUID job = kicks.job(payment);
            fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(job)), "kick sent");

            assertThat(fake.received()).singleElement().satisfies(bytes ->
                    assertThat(HexFormat.of().formatHex(bytes)).isEqualTo(DrawerKicks.KICK_BYTES));
            assertThat(fixtures.jobStatus(waiting)).isEqualTo("RETRY_WAIT");
        }
    }

    /** A cash payment's kick on the till, run through the worker until it is FAILED; its job. */
    private UUID kicked(Till till) throws Exception {
        UUID payment = UUID.randomUUID();
        kicks.requested(till, "PAYMENT", payment);
        await(() -> kicks.handled(payment) == 1, "kick handled");
        UUID job = kicks.job(payment);
        fixtures.runWorkerUntil(() -> "FAILED".equals(fixtures.jobStatus(job)), "kick failed");
        return job;
    }
}
