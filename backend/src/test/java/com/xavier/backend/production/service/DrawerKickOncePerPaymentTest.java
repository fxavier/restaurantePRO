package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * D158, A66 (production half): settlement's {@code CashDrawerKickRequested} for a cash payment or a cash refund puts
 * one {@code DRAWER_KICK} ticket and one job on the terminal's receipt printer, whose payload on the wire is exactly
 * {@code ESC p m t1 t2} from its profile; a redelivered event kicks no second time (ticket key
 * {@code (DRAWER_KICK, source, 'DRAWER')}). A printer without a drawer, or a terminal without a printer, gets
 * nothing: no ticket, no job (a kick is never kept for later). That a card payment or a credit sale requests no kick
 * is settlement's half ({@code SettlementPublishesKickTest}).
 */
@ProductionModuleTest
class DrawerKickOncePerPaymentTest {

    @Autowired
    DrawerKicks kicks;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Test
    void aCashPaymentKicksTheTerminalsDrawerOnceEvenWhenRedelivered() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);
            UUID payment = UUID.randomUUID();

            kicks.requested(till, "PAYMENT", payment);
            await(() -> kicks.handled(payment) == 1, "kick handled");
            UUID job = kicks.job(payment);
            fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(job)), "kick sent");

            kicks.requested(till, "PAYMENT", payment);
            await(() -> kicks.handled(payment) == 2, "redelivery handled");

            assertThat(kicks.tickets(payment)).singleElement().satisfies(ticket -> assertThat(ticket)
                    .containsEntry("source_type", "DRAWER_KICK").containsEntry("destination", "DRAWER")
                    .containsEntry("content_format", "KICK").containsEntry("terminal_id", till.terminal())
                    .containsEntry("printer_id", till.printer()).containsEntry("routing", "ROUTED"));
            assertThat(kicks.jobs(payment)).singleElement().satisfies(row -> assertThat(row)
                    .containsEntry("printer_id", till.printer()).containsEntry("attempts", 1));
            assertThat(fake.received()).singleElement().satisfies(bytes ->
                    assertThat(HexFormat.of().formatHex(bytes)).isEqualTo(DrawerKicks.KICK_BYTES));
        }
    }

    /** Also on a printer with the status probe on: a kick is sent without it (paper out does not stop a drawer). */
    @Test
    void aCashRefundKicksOnce() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(),
                    "\"paperWidthMm\": 58, \"columns\": 32, \"statusProbe\": true, \"drawerPin\": 1, "
                            + "\"drawerPulseMs\": 200");
            UUID refund = UUID.randomUUID();

            kicks.requested(till, "REFUND", refund);
            await(() -> kicks.handled(refund) == 1, "kick handled");
            UUID job = kicks.job(refund);
            fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(job)), "kick sent");

            assertThat(kicks.tickets(refund)).singleElement().satisfies(ticket -> assertThat(ticket)
                    .containsEntry("source_type", "DRAWER_KICK"));
            assertThat(fake.received()).singleElement().satisfies(bytes ->
                    assertThat(HexFormat.of().formatHex(bytes)).isEqualTo("1b700164fa"));
        }
    }

    @Test
    void aPrinterWithoutADrawerGetsNoKick() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), "\"feedLines\": 4");
        UUID payment = UUID.randomUUID();

        kicks.requested(till, "PAYMENT", payment);
        await(() -> kicks.handled(payment) == 1, "kick handled");

        assertThat(kicks.tickets(payment)).isEmpty();
        assertThat(kicks.jobs(payment)).isEmpty();
    }

    @Test
    void aTerminalWithoutAPrinterGetsNoKickAndNothingUnrouted() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);
        UUID payment = UUID.randomUUID();

        kicks.requested(new Till(till.store(), UUID.randomUUID(), till.printer()), "PAYMENT", payment);
        await(() -> kicks.handled(payment) == 1, "kick handled");

        assertThat(kicks.tickets(payment)).isEmpty();
    }
}
