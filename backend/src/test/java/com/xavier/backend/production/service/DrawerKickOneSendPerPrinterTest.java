package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.DrawerKicks;
import com.xavier.backend.production.DrawerKicks.Till;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D50 §7.2 with D158: a printer gets one connection at a time, kicks included. A claim run that takes a kick claims
 * nothing else for that printer: neither its queued ticket nor a second kick (they go out on later runs). Everything
 * is created and claimed in one rolled-back transaction, so no other worker sees it.
 */
@ProductionModuleTest
class DrawerKickOneSendPerPrinterTest {

    /** Enough for every due head of the shared database: the run reaches this test's jobs. */
    static final int ALL = 10_000;

    @Autowired
    PrintQueue queue;

    @Autowired
    DrawerKickService service;

    @Autowired
    DrawerKicks kicks;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aKickIsThePrintersOnlySendOfTheRun() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);
        fixtures.mode(till.store(), till.printer(), "HOLD");
        UUID page = fixtures.testPage(till.store(), till.printer());

        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.sql("UPDATE production.printer SET mode = 'NORMAL' WHERE id = ?").param(till.printer()).update();
            service.kick("PAYMENT", UUID.randomUUID(), till.store(), till.terminal());
            service.kick("REFUND", UUID.randomUUID(), till.store(), till.terminal());

            List<Dispatch> sends = onPrinter(queue.claimDue(ALL), till.printer());

            assertThat(sends).singleElement().satisfies(send ->
                    assertThat(HexFormat.of().formatHex(send.payload())).isEqualTo(DrawerKicks.KICK_BYTES));
            assertThat(fixtures.jobStatus(page)).isEqualTo("QUEUED");
        });
    }

    @Test
    void aKickWaitsWhileItsPrinterIsSending() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);
        fixtures.mode(till.store(), till.printer(), "HOLD");
        UUID page = fixtures.testPage(till.store(), till.printer());

        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.sql("UPDATE production.printer SET mode = 'NORMAL' WHERE id = ?").param(till.printer()).update();
            jdbc.sql("UPDATE production.print_job SET status = 'PROCESSING', attempts = 1, started_at = now() "
                    + "WHERE id = ?").param(page).update();
            UUID payment = UUID.randomUUID();
            service.kick("PAYMENT", payment, till.store(), till.terminal());

            assertThat(onPrinter(queue.claimDue(ALL), till.printer())).isEmpty();
            assertThat(fixtures.jobStatus(kicks.job(payment))).isEqualTo("QUEUED");
        });
    }

    private static List<Dispatch> onPrinter(List<Dispatch> sends, UUID printer) {
        return sends.stream().filter(send -> send.printerId().equals(printer)).toList();
    }
}
