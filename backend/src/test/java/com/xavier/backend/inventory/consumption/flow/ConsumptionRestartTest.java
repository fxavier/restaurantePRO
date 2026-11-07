package com.xavier.backend.inventory.consumption.flow;

import static com.xavier.backend.inventory.consumption.flow.LiveStore.roundPublications;
import static com.xavier.backend.inventory.consumption.flow.LiveStore.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.xavier.backend.BackendApplication;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.consumption.service.RoundConsumption;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementTestClock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D116, D133, A44 (the restart half): a consumption that crashed leaves its publication incomplete; a second
 * application on the same database delivers it again at startup and the round is consumed once; a forced redelivery
 * afterwards posts nothing new.
 */
@ConsumptionFlowTest
class ConsumptionRestartTest {

    @Autowired
    SettlementFixtures sales;

    @Autowired
    InventoryFixtures inventory;

    @Autowired
    RoundConsumption consumption;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aCrashedConsumptionIsDeliveredAfterARestartAndConsumedOnce() throws Exception {
        Sale sale = sales.sale("1");
        waitUntil(() -> roundPublications(jdbc, sale.order(), true) == 1, "the first round's consumption");
        LiveStore live = LiveStore.of(sale, clock.instant(), mvc, inventory);
        RoundConsumption crashing = AopTestUtils.getUltimateTargetObject(consumption);
        doThrow(new IllegalStateException("crash before consumption finished")).when(crashing).consume(any());
        try {
            sales.confirmRound(sale.shop(), sale.cashier(), sale.order(), "2",
                    sales.orderVersion(sale.cashier(), sale.order()));
            verify(crashing, timeout(10_000)).consume(lastBatch(sale.order()));
            waitUntil(() -> roundPublications(jdbc, sale.order(), false) == 1, "the publication left incomplete");
        } finally {
            reset(crashing);
        }
        assertThat(live.movements(jdbc, "SALE_CONSUMPTION")).isZero();

        try (ConfigurableApplicationContext restarted = restart()) {
            waitUntil(() -> live.movements(jdbc, "SALE_CONSUMPTION") == 1, "consumed by the restarted application");
            waitUntil(() -> roundPublications(jdbc, sale.order(), false) == 0, "the publication completed");
        }
        assertThat(live.flourInStock(jdbc)).isEqualByComparingTo("99.5");

        consumption.consume(lastBatch(sale.order()));

        assertThat(live.movements(jdbc, "SALE_CONSUMPTION")).isEqualTo(1);
        assertThat(live.flourInStock(jdbc)).isEqualByComparingTo("99.5");
    }

    private UUID lastBatch(UUID order) {
        return jdbc.sql("SELECT id FROM orders.order_batch WHERE order_id = ? ORDER BY batch_no DESC LIMIT 1")
                .param(order).query(UUID.class).single();
    }

    private static ConfigurableApplicationContext restart() {
        var postgres = TestcontainersConfiguration.postgres();
        return new SpringApplicationBuilder(BackendApplication.class)
                .properties(
                        // application.yaml reads these placeholders; builder properties have the lowest precedence
                        "RESTAURANTPRO_DB_URL=" + postgres.getJdbcUrl(),
                        "RESTAURANTPRO_DB_USERNAME=" + postgres.getUsername(),
                        "RESTAURANTPRO_DB_PASSWORD=" + postgres.getPassword(),
                        "RESTAURANTPRO_MANAGEMENT_PORT=0",
                        "server.port=0")
                .run();
    }
}
