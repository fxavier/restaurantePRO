package com.xavier.backend.orders;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The R3 billing view (D66, D84): the table an order sits at, and per line the dimensions reporting resolves (item,
 * catalog release, who served it and when).
 */
@OrdersModuleTest
class BillingViewR3Test {

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID opener = UUID.randomUUID();
    final UUID confirmer = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;

    @BeforeEach
    void aSeatedOrder() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, opener, terminal));
    }

    @Test
    void aSeatedOrdersViewIsDineInAndNamesItsTable() {
        OrderBillingView view = inPos(() -> ordersApi.billingView(order));

        assertThat(view.tableId()).isEqualTo(shop.table());
        assertThat(view.tableCode()).isEqualTo("M1");
        assertThat(view.mode()).isEqualTo(OrderMode.DINE_IN);
    }

    @Test
    void aWalkInOrderHasItsModeAndNoTable() throws Exception {
        UUID takeaway = id(OrdersFixtures.json(mvc.perform(post(OrdersFixtures.POS + "/orders")
                        .with(OrdersActors.pos(shop.store(), opener, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"TAKEAWAY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()));

        OrderBillingView view = inPos(() -> ordersApi.openBillingViews(shop.store())).stream()
                .filter(open -> open.orderId().equals(takeaway)).findFirst().orElseThrow();

        assertThat(view.tableId()).isNull();
        assertThat(view.tableCode()).isNull();
        assertThat(view.mode()).isEqualTo(OrderMode.TAKEAWAY);
    }

    @Test
    void aBilledLineCarriesItsItemReleaseConfirmerAndConfirmationInstant() throws Exception {
        UUID line = fixtures.confirmedLine(shop, order, "2", 0, confirmer, terminal);
        Map<String, Object> batch = jdbc.sql("""
                        SELECT b.release_id, b.confirmed_at, l.item_code FROM orders.order_batch b
                        JOIN orders.order_line l ON l.batch_id = b.id WHERE l.id = ?""")
                .param(line).query().singleRow();

        BillingLine billed = inPos(() -> ordersApi.billingView(order)).lines().getFirst();

        assertThat(billed.lineId()).isEqualTo(line);
        assertThat(billed.itemId()).isEqualTo(shop.item());
        assertThat(billed.itemCode()).isEqualTo(batch.get("item_code")).isNotNull();
        assertThat(billed.releaseId()).isEqualTo(batch.get("release_id")).isNotNull();
        assertThat(billed.servedByEmployeeId()).isEqualTo(confirmer);
        assertThat(billed.confirmedAt()).isEqualTo(instant(batch.get("confirmed_at")));
        assertThat(billed.taxCode()).isNotBlank();
    }

    static Instant instant(Object timestamp) {
        return timestamp instanceof OffsetDateTime offset ? offset.toInstant()
                : ((java.sql.Timestamp) timestamp).toInstant();
    }

    <T> T inPos(Supplier<T> action) {
        return OrdersActors.runAsPos(shop.store(), opener, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }
}
