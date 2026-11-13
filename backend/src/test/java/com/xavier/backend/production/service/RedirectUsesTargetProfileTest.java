package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionFixtures.Round;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.production.model.CodePage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D155: a ticket is laid out at send time for the printer that prints it. A round routed to an 80 mm printer and
 * redirected (D56) to a 58 mm one prints at the 58 mm printer's width, with its cut mode.
 */
@ProductionModuleTest
class RedirectUsesTargetProfileTest {

    @Autowired
    ProductionFixtures fixtures;

    @Test
    void aRedirectedRoundPrintsWithTheTargetPrintersWidthAndCut() throws Exception {
        Shop shop = fixtures.shop();
        try (TcpFakePrinter narrow = TcpFakePrinter.accepting()) {
            UUID wide = fixtures.printer(shop.store(), "W" + suffix(), "127.0.0.1", TcpFakePrinter.closedPort(), """
                    "paperWidthMm": 80, "columns": 48, "cutMode": "FULL\"""");
            fixtures.routes(shop.store(), Map.of(ProductionFixtures.DESTINATION, wide)).andExpect(status().isOk());
            fixtures.mode(shop.store(), wide, "HOLD");
            Round round = fixtures.confirmedRound(shop, UUID.randomUUID(), UUID.randomUUID(), "1");
            await(() -> !fixtures.jobsOfSource(round.batch()).isEmpty(), "job of the round");
            UUID job = (UUID) fixtures.jobsOfSource(round.batch()).getFirst().get("id");
            UUID target = fixtures.printer(shop.store(), "N" + suffix(), narrow.host(), narrow.port(), """
                    "paperWidthMm": 58, "columns": 32, "cutMode": "NONE\"""");

            fixtures.changeMode(shop.store(), wide, "REDIRECT", target, Permission.PRODUCTION_MANAGE)
                    .andExpect(status().isOk());
            fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(job)), "redirected job sent");

            List<String> printed = narrow.awaitPayloads(CodePage.CP860.charset(),
                    text -> text.contains("Pedido #" + round.orderNumber()), 1, Duration.ofSeconds(5));
            assertThat(printed).singleElement().satisfies(text -> assertThat(text)
                    .contains("\n" + "-".repeat(32) + "\n").doesNotContain("-".repeat(33))
                    .endsWith("\u001bd\u0004").doesNotContain("\u001dV"));
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
