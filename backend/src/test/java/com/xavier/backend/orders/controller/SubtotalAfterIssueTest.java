package com.xavier.backend.orders.controller;

import static com.xavier.backend.orders.OrdersFixtures.POS;
import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P27: once the order is frozen for its document, a subtotal would put a second, non-fiscal bill next to the fiscal
 * one; the POS reprints the document as a copy instead.
 */
@OrdersModuleTest
class SubtotalAfterIssueTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersApi ordersApi;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();

    @Test
    void aSubtotalOfAnOrderFrozenForItsDocumentIsRefusedAndPrintsNothing() throws Exception {
        Shop shop = fixtures.shop();
        UUID order = id(fixtures.openedOrder(shop, employee, terminal));
        fixtures.confirmedLine(shop, order, "1", 0, employee, terminal);
        fixtures.inPos(shop, () -> ordersApi.freezeForInvoice(order));

        mvc.perform(post(POS + "/orders/" + order + "/subtotal/print")
                        .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ISSUED"));

        assertThat(fixtures.audits("ORDER_SUBTOTAL_REQUESTED", order)).isZero();
        assertThat(fixtures.count("""
                SELECT count(*) FROM dining.table_occupation WHERE order_id = ? AND subtotal_printed_at IS NOT NULL""",
                order)).isZero();
    }
}
