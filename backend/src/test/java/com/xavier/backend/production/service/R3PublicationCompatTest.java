package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionFixtures.Round;
import com.xavier.backend.production.ProductionModuleTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D129: a round publication stored by R3 has neither {@code mode} nor line {@code components}. Read back by the
 * registry's serializer, it still prints, with the R3 table/order header. The round itself is real (so marking it
 * DISPATCHED again is a no-op); the stored R3 publication carries a KITCHEN line, which has no route here, so its
 * ticket is kept UNROUTED and no worker can change it.
 */
@ProductionModuleTest
class R3PublicationCompatTest {

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    TicketService tickets;

    @Autowired
    EventSerializer serializer;

    @Autowired
    TransactionTemplate tx;

    @Test
    void anR3RoundPublicationPrintsWithTheR3Header() throws Exception {
        Shop shop = fixtures.shop();
        UUID employee = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");
        await(() -> "DISPATCHED".equals(fixtures.batchStatus(round.batch())), "batch dispatched");

        OrderBatchConfirmed r3 = serializer.deserialize("""
                {"orderId": "%s", "batchId": "%s", "batchNo": 1, "storeId": "%s", "terminalId": "%s",
                 "orderNumber": %d, "tableCode": "M1", "employeeId": "%s", "confirmedAt": "2026-11-04T12:00:00Z",
                 "lines": [{"lineId": "%s", "itemCode": "S1", "description": "Sopa", "variantName": null,
                            "quantity": 1.000, "notes": null, "destination": "KITCHEN"}]}""".formatted(
                round.order(), round.batch(), shop.store(), terminal, round.orderNumber(), employee,
                UUID.randomUUID()), OrderBatchConfirmed.class);
        tx.executeWithoutResult(status -> tickets.receiveBatch(r3));

        assertThat(r3.mode()).isNull();
        assertThat(r3.lines().getFirst().components()).isNull();
        Map<String, Object> kitchen = fixtures.tickets(round.batch()).stream()
                .filter(ticket -> "KITCHEN".equals(ticket.get("destination"))).findFirst().orElseThrow();
        assertThat((String) kitchen.get("content")).contains("Mesa M1 - Pedido #" + round.orderNumber(), "1 x Sopa");
        assertThat(fixtures.batchStatus(round.batch())).isEqualTo("DISPATCHED");
    }
}
