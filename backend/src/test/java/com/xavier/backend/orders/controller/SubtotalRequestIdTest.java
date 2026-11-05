package com.xavier.backend.orders.controller;

import static com.xavier.backend.orders.OrdersFixtures.POS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrderEventProbe;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.model.event.SubtotalLine;
import com.xavier.backend.orders.model.event.SubtotalRequested;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D89 (2): every subtotal command draws its own request id (UUIDv7), so production can print a deliberate second
 * subtotal and still drop a redelivered event. An Idempotency-Key replay returns the stored response and publishes
 * nothing. A publication stored by R2, without the id, still deserializes (production then falls back to the
 * content digest).
 */
@OrdersModuleTest
class SubtotalRequestIdTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrderEventProbe events;

    @Autowired
    EventSerializer serializer;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;

    @BeforeEach
    void anOpenTableWithALine() throws Exception {
        shop = fixtures.shop();
        order = fixtures.openedAt(shop, shop.table(), employee, terminal);
        fixtures.confirmedLine(shop, order, "1", 0, employee, terminal);
    }

    @Test
    void twoSubtotalCommandsCarryTwoDifferentRequestIds() throws Exception {
        subtotal(UUID.randomUUID().toString()).andExpect(status().isAccepted());
        subtotal(UUID.randomUUID().toString()).andExpect(status().isAccepted());

        List<UUID> ids = events.subtotalsOf(order, 2).stream().map(SubtotalRequested::requestId).toList();

        assertThat(ids).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
    }

    @Test
    void aReplayOfTheSameKeyPublishesNothingNew() throws Exception {
        String key = UUID.randomUUID().toString();
        subtotal(key).andExpect(status().isAccepted());
        long published = publications();

        subtotal(key).andExpect(status().isAccepted());

        assertThat(published).isPositive();
        assertThat(publications()).isEqualTo(published);
        assertThat(events.subtotalsOf(order, 1)).singleElement()
                .satisfies(event -> assertThat(event.requestId()).isNotNull());
    }

    @Test
    void aPublicationStoredByR2WithoutARequestIdDeserializesWithANullId() {
        UUID r2Order = UUID.fromString("0190d000-0000-7000-8000-0000000000d1");
        String r2Payload = """
                {"orderId":"0190d000-0000-7000-8000-0000000000d1","storeId":"0190d000-0000-7000-8000-0000000000d2",\
                "terminalId":"0190d000-0000-7000-8000-0000000000d3","orderNumber":77,"tableCode":"M1",\
                "lines":[{"description":"Coca-Cola 33cl","netQty":2.000,\
                "unitPrice":{"amount":12.50,"currency":"MZN"},"gross":{"amount":25.00,"currency":"MZN"}}],\
                "due":{"amount":25.00,"currency":"MZN"}}""";

        SubtotalRequested event = serializer.deserialize(r2Payload, SubtotalRequested.class);

        assertThat(event.orderId()).isEqualTo(r2Order);
        assertThat(event.orderNumber()).isEqualTo(77);
        assertThat(event.lines()).containsExactly(new SubtotalLine("Coca-Cola 33cl", new BigDecimal("2.000"),
                Money.of("12.50", MZN), Money.of("25.00", MZN)));
        assertThat(event.due()).isEqualTo(Money.of("25.00", MZN));
        assertThat(event.requestId()).isNull();
    }

    long publications() {
        return fixtures.count("SELECT count(*) FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?",
                SubtotalRequested.class.getName(), "%" + order + "%");
    }

    ResultActions subtotal(String key) throws Exception {
        return mvc.perform(post(POS + "/orders/" + order + "/subtotal/print")
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                .header("Idempotency-Key", key));
    }
}
