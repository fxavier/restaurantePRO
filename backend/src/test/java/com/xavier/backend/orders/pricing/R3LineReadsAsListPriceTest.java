package com.xavier.backend.orders.pricing;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D101, V6009: a line confirmed before R4 has no list price stored. It reads as sold at list price: its list price is
 * its unit price, with no discount label and no origin, and its amounts do not move.
 */
@OrdersModuleTest
class R3LineReadsAsListPriceTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aLineWithoutAListPriceReadsAtItsUnitPrice() throws Exception {
        UUID operator = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        Shop shop = fixtures.shop();
        UUID order = id(fixtures.openedOrder(shop, operator, terminal));
        UUID round = fixtures.confirmedLine(shop, order, "1", 0, operator, terminal);
        UUID line = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders.order_line (id, order_id, status, item_id, quantity, created_at,
                    created_by_employee_id, item_code, description, unit_price, currency, tax_category_id, tax_code,
                    tax_rate, release_id, catalog_version, confirmed_at, confirmed_by_employee_id, batch_id, version)
                SELECT ?, order_id, 'CONFIRMED', item_id, 2, created_at, created_by_employee_id, item_code,
                    description, unit_price, currency, tax_category_id, tax_code, tax_rate, release_id,
                    catalog_version, confirmed_at, confirmed_by_employee_id, batch_id, 0
                FROM orders.order_line WHERE id = ?""").param(line).param(round).update(); // the R3 shape

        BillingLine billed = ordersApi.billingView(order).lines().stream()
                .filter(candidate -> candidate.lineId().equals(line)).findFirst().orElseThrow();
        assertThat(billed.listUnitPrice()).isEqualTo(billed.unitPrice());
        assertThat(billed.discountLabel()).isNull();
        assertThat(billed.gross().amount()).isEqualByComparingTo("25.00");
        assertThat(jdbc.sql("SELECT list_unit_price IS NULL FROM orders.order_line WHERE id = ?").param(line)
                .query(Boolean.class).single()).isTrue();
        assertThat(fixtures.order(shop, order).get("lines").get(1).get("origin").isNull()).isTrue();
    }
}
