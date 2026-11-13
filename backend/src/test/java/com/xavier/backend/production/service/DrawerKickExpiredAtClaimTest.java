package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.DrawerKicks;
import com.xavier.backend.production.DrawerKicks.Till;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D158 through the worker: a kick first claimed more than 30 s after it was created (the worker or the printer was
 * busy, or the backend restarted) is FAILED {@code KICK_EXPIRED} and never sent; a younger one is sent. A kick whose
 * printer lost its drawer since it was queued is FAILED {@code NO_CASH_DRAWER}. Each kick is created and changed in
 * one transaction, so no worker sees it before.
 */
@ProductionModuleTest
class DrawerKickExpiredAtClaimTest {

    @Autowired
    DrawerKickService service;

    @Autowired
    DrawerKicks kicks;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aKickClaimedAfterThirtySecondsExpiresUnsent() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);

            UUID job = agedKick(till, "45 seconds");
            fixtures.runWorkerUntil(() -> "FAILED".equals(fixtures.jobStatus(job)), "kick expired");

            assertThat(fixtures.job(job)).containsEntry("last_error", "KICK_EXPIRED").containsEntry("attempts", 0);
            assertThat(fake.received()).isEmpty();
        }
    }

    @Test
    void aKickClaimedWithinThirtySecondsIsSent() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);

            UUID job = agedKick(till, "15 seconds");
            fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(job)), "kick sent");

            assertThat(fake.received()).hasSize(1);
        }
    }

    @Test
    void aKickWhosePrinterLostItsDrawerFailsUnsent() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);
            UUID payment = UUID.randomUUID();
            tx.executeWithoutResult(status -> {
                service.kick("PAYMENT", payment, till.store(), till.terminal(), Instant.now());
                jdbc.sql("UPDATE production.printer SET drawer_pin = NULL WHERE id = ?").param(till.printer())
                        .update();
            });
            UUID job = kicks.job(payment);

            fixtures.runWorkerUntil(() -> "FAILED".equals(fixtures.jobStatus(job)), "kick failed");

            assertThat(fixtures.job(job)).containsEntry("last_error", "NO_CASH_DRAWER");
            assertThat(fake.received()).isEmpty();
        }
    }

    private UUID agedKick(Till till, String age) {
        UUID payment = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            service.kick("PAYMENT", payment, till.store(), till.terminal(), Instant.now());
            jdbc.sql("""
                    UPDATE production.print_job SET created_at = created_at - CAST(? AS interval),
                        next_attempt_at = next_attempt_at - CAST(? AS interval)
                    WHERE ticket_id IN (SELECT id FROM production.production_ticket WHERE source_id = ?)""")
                    .param(age).param(age).param(payment).update();
        });
        return kicks.job(payment);
    }
}
