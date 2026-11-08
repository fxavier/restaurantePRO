package com.xavier.backend.orders.discounts;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S53 review: an order with plain discounts and promotions, no copy and no reversal, bills the labels it billed before
 * S53 (8e54433): the promotion's name, "Desconto 10%", "Desconto", "Oferta" and none at list price. A fiscal document
 * copies them from these billing lines.
 */
@OrdersModuleTest
class DiscountLabelsUnchangedTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    OrdersApi ordersApi;

    final UUID operator = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();

    @Test
    void plainDiscountsAndPromotionsKeepTheirLabels() throws Exception {
        Shop shop = fixtures.shop();
        UUID order = id(fixtures.openedOrder(shop, operator, terminal));
        UUID atList = fixtures.confirmedLine(shop, order, "1", 0, operator, terminal);
        String code = OrdersPromotions.code("L");
        String name = promotions.promotion(shop.store(), shop.item(), code,
                "\"kind\": \"AMOUNT_OFF_UNIT\", \"amount\": 0.50", null).get("name").asString();
        long promotionVersion = promotions.publish(shop.store());
        List<UUID> lines = List.of(id(fixtures.draft(shop, order, "1", operator, terminal)),
                id(fixtures.draft(shop, order, "1", operator, terminal)),
                id(fixtures.draft(shop, order, "1", operator, terminal)),
                id(fixtures.draft(shop, order, "1", operator, terminal)));
        fixtures.confirm(shop, order, """
                {"orderVersion": %d, "catalogVersion": %d, "lineIds": [%s], "promotionVersion": %d}"""
                .formatted(version(order), shop.catalogVersion(), String.join(",", lines.stream()
                        .map(line -> "\"" + line + "\"").toList()), promotionVersion), operator, terminal)
                .andExpect(status().isCreated());
        Employee manager = fixtures.employee(shop, IdentityFixtures.MANAGER);
        adjust(shop, order, lines.get(1), "\"kind\": \"DISCOUNT\", \"percent\": 5", Permission.ORDER_DISCOUNT, null);
        adjust(shop, order, lines.get(2), "\"kind\": \"DISCOUNT\", \"unitPrice\": 11.50", Permission.ORDER_DISCOUNT,
                null);
        adjust(shop, order, lines.get(3), "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, manager);

        assertThat(ordersApi.billingView(order).lines()).extracting(billed -> billed.unitPrice().amount()
                        .toPlainString() + "@" + billed.discountLabel())
                .containsExactly("12.50@null", "12.00@" + name, "11.40@Desconto 5%", "11.50@Desconto", "0.00@Oferta");
        assertThat(ordersApi.billingView(order).lines().getFirst().lineId()).isEqualTo(atList);
    }

    private void adjust(Shop shop, UUID order, UUID line, String values, Permission permission, Employee approver)
            throws Exception {
        fixtures.discount(shop, order, """
                {"orderVersion": %d, "lines": [{"lineId": "%s"}], %s, "reason": "regular"}"""
                .formatted(version(order), line, values), operator, terminal, permission, approver)
                .andExpect(status().isOk());
    }

    private long version(UUID order) {
        return ordersApi.billingView(order).version();
    }
}
