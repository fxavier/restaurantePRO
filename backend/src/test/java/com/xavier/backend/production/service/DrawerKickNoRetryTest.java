package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.DrawerKicks;
import com.xavier.backend.production.DrawerKicks.Till;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D158: a kick gets one attempt. A printer that refuses the connection (nothing written) fails it at once, with no
 * RETRY_WAIT and no backoff: a drawer opening seconds later, unattended, is a risk. The cashier uses the key or the
 * manual command instead.
 */
@ProductionModuleTest
class DrawerKickNoRetryTest {

    @Autowired
    DrawerKicks kicks;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    PrintWorker worker;

    @Test
    void aPrinterThatIsDownFailsTheKickAtItsFirstAttempt() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);
        UUID payment = UUID.randomUUID();

        kicks.requested(till, "PAYMENT", payment);
        await(() -> kicks.handled(payment) == 1, "kick handled");
        UUID job = kicks.job(payment);
        fixtures.runWorkerUntil(() -> "FAILED".equals(fixtures.jobStatus(job)), "kick failed");
        worker.poll();

        assertThat(fixtures.job(job)).containsEntry("status", "FAILED").containsEntry("attempts", 1)
                .containsEntry("last_error", "NOT_CONNECTED");
    }
}
