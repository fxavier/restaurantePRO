package com.xavier.backend.orders;

import static com.xavier.backend.orders.OrdersFixtures.id;

import com.xavier.backend.dining.DiningChangeStream;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** D60, A14 (orders half): opening a table notifies TABLES and ORDER; a draft and a round notify ORDER, ids only. */
@OrdersModuleTest
class OrderNotificationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();

    @Test
    void openingATableAndConfirmingARoundNotifyTheStoreWithIdsOnly() throws Exception {
        Shop shop = fixtures.shop();
        DiningChangeStream stream = DiningChangeStream.subscribe(mvc, shop.store());

        UUID order = id(fixtures.openedOrder(shop, employee, terminal));
        await(stream, "TABLES", shop.table());
        await(stream, "ORDER", order);
        int afterOpening = count(stream, order);

        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));
        fixtures.confirm(shop, order, 0, shop.catalogVersion(), List.of(line), employee, terminal,
                UUID.randomUUID().toString());
        awaitCount(stream, order, afterOpening + 2);
    }

    static String notice(String topic, UUID id) {
        return "{\"topic\":\"" + topic + "\",\"id\":\"" + id + "\"}";
    }

    static int count(DiningChangeStream stream, UUID order) {
        return stream.received().split(java.util.regex.Pattern.quote(notice("ORDER", order)), -1).length - 1;
    }

    static void await(DiningChangeStream stream, String topic, UUID id) throws InterruptedException {
        poll(() -> stream.received().contains(notice(topic, id)), topic + " " + id, stream);
    }

    static void awaitCount(DiningChangeStream stream, UUID order, int expected) throws InterruptedException {
        poll(() -> count(stream, order) >= expected, expected + " ORDER notices", stream);
    }

    static void poll(java.util.function.BooleanSupplier done, String what, DiningChangeStream stream)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("No " + what + " in: " + stream.received());
            }
            Thread.sleep(20);
        }
    }
}
