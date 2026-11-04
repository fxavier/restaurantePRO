package com.xavier.backend.orders.controller;

import static com.xavier.backend.orders.OrdersFixtures.POS;
import static com.xavier.backend.orders.OrdersFixtures.id;
import static com.xavier.backend.orders.OrdersFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrderEventProbe;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Confirming a round (D50 §7.1): version check, catalog check, snapshot on the drafts, the batch and its
 * {@link OrderBatchConfirmed} publication, all in one transaction. A3 (line half), A4 (publication half).
 */
@OrdersModuleTest
class BatchConfirmationHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrderEventProbe events;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;

    @BeforeEach
    void anOpenTable() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, employee, terminal));
    }

    @Test
    void confirmingARoundSnapshotsTheCatalogOntoItsLinesAndMovesTheOrderVersion() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "2", employee, terminal));

        confirm(0, shop.catalogVersion(), List.of(line), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchNo").value(1))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.orderVersion").value(1))
                .andExpect(jsonPath("$.lines[0].id").value(line.toString()))
                .andExpect(jsonPath("$.lines[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.lines[0].description").value("Coca-Cola 33cl"))
                .andExpect(jsonPath("$.lines[0].unitPrice.amount").value(12.50))
                .andExpect(jsonPath("$.lines[0].taxRatePercent").value(16))
                .andExpect(jsonPath("$.lines[0].gross.amount").value(25.00))
                .andExpect(jsonPath("$.lines[0].preview").value(false));

        getOrder().andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.due.amount").value(25.00));
        assertThat(fixtures.audits("ORDER_BATCH_CONFIRMED", order)).isOne();
    }

    @Test
    void aStaleOrderVersionIsAConflictAndTheDraftStays() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));

        confirm(7, shop.catalogVersion(), List.of(line), UUID.randomUUID().toString())
                .andExpect(status().isConflict());

        getOrder().andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.lines[0].status").value("DRAFT"));
    }

    @Test
    void draftsPricedFromAnOlderCatalogAreRefusedAsCatalogChanged() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));
        long current = fixtures.republish(shop.store());

        confirm(0, shop.catalogVersion(), List.of(line), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_CHANGED"))
                .andExpect(jsonPath("$.catalogVersion").value(current));

        getOrder().andExpect(jsonPath("$.lines[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.catalogVersion").value(current));
        confirm(0, current, List.of(line), UUID.randomUUID().toString()).andExpect(status().isCreated());
    }

    @Test
    void aLineThatIsNotADraftOfThisOrderIsAConflict() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));
        confirm(0, shop.catalogVersion(), List.of(line), UUID.randomUUID().toString()).andExpect(status().isCreated());

        confirm(1, shop.catalogVersion(), List.of(line), UUID.randomUUID().toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LINE_NOT_DRAFT"));
        confirm(1, shop.catalogVersion(), List.of(UUID.randomUUID()), UUID.randomUUID().toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LINE_NOT_DRAFT"));
    }

    @Test
    void aConfirmedLineKeepsItsPriceTaxAndNameAfterARepublicationWithNewPrices() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "2", employee, terminal));
        confirm(0, shop.catalogVersion(), List.of(line), UUID.randomUUID().toString()).andExpect(status().isCreated());

        fixtures.reprice(shop, "20.00");
        fixtures.republish(shop.store());

        getOrder().andExpect(jsonPath("$.lines[0].unitPrice.amount").value(12.50))
                .andExpect(jsonPath("$.lines[0].taxRatePercent").value(16))
                .andExpect(jsonPath("$.lines[0].description").value("Coca-Cola 33cl"))
                .andExpect(jsonPath("$.due.amount").value(25.00));
        UUID fresh = id(fixtures.draft(shop, order, "1", employee, terminal));
        getOrder().andExpect(jsonPath("$.lines[?(@.id == '%s')].unitPrice.amount".formatted(fresh)).value(20.00));
    }

    @Test
    void theRoundIsPublishedWithItsWholeContentAndASecondRoundCarriesOnlyItsNewLines() throws Exception {
        UUID first = id(fixtures.draft(shop, order, "2", employee, terminal));
        JsonNode round1 = json(confirm(0, shop.catalogVersion(), List.of(first), UUID.randomUUID().toString())
                .andReturn().getResponse().getContentAsString());
        UUID second = id(fixtures.draft(shop, order, "0.5", employee, terminal));
        JsonNode round2 = json(confirm(1, shop.catalogVersion(), List.of(second), UUID.randomUUID().toString())
                .andExpect(jsonPath("$.batchNo").value(2)).andReturn().getResponse().getContentAsString());

        List<OrderBatchConfirmed> received = events.receivedFor(order, 2);
        assertThat(received).extracting(OrderBatchConfirmed::batchNo).containsExactly(1, 2);
        OrderBatchConfirmed two = received.get(1);
        assertThat(two.batchId()).isEqualTo(id(round2));
        assertThat(two.storeId()).isEqualTo(shop.store());
        assertThat(two.terminalId()).isEqualTo(terminal);
        assertThat(two.employeeId()).isEqualTo(employee);
        assertThat(two.lines()).extracting(Line::lineId).containsExactly(second);
        assertThat(two.lines().getFirst().quantity()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(two.lines().getFirst().destination()).isEqualTo("BAR");
        assertThat(two.lines().getFirst().description()).isEqualTo("Coca-Cola 33cl");
        assertThat(publications(id(round1))).isOne();
        assertThat(publications(id(round2))).isOne();
    }

    @Test
    void whenTheTransactionRollsBackThereIsNeitherARoundNorAPublication() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));
        events.failRoundsOf(order);

        confirm(0, shop.catalogVersion(), List.of(line), UUID.randomUUID().toString())
                .andExpect(status().isInternalServerError());

        assertThat(fixtures.count("SELECT count(*) FROM orders.order_batch WHERE order_id = ?", order)).isZero();
        assertThat(fixtures.count("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                "%" + order + "%")).isZero();
        getOrder().andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.lines[0].status").value("DRAFT"));
    }

    @Test
    void aRetryWithTheSameKeyReplaysTheRoundWithoutASecondBatch() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));
        String key = UUID.randomUUID().toString();
        String first = confirm(0, shop.catalogVersion(), List.of(line), key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String replay = confirm(0, shop.catalogVersion(), List.of(line), key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(json(replay)).isEqualTo(json(first));
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_batch WHERE order_id = ?", order)).isOne();
    }

    @Test
    void aLockedTableTakesNoNewRound() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));
        fixtures.lockTable(shop, shop.table());

        confirm(0, shop.catalogVersion(), List.of(line), UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("TABLE_LOCKED"));
    }

    long publications(UUID batchId) {
        return fixtures.count("SELECT count(*) FROM event_publication WHERE event_type LIKE ? "
                + "AND serialized_event LIKE ?", "%OrderBatchConfirmed", "%" + batchId + "%");
    }

    ResultActions confirm(long orderVersion, long catalogVersion, List<UUID> lines, String key) throws Exception {
        return fixtures.confirm(shop, order, orderVersion, catalogVersion, lines, employee, terminal, key);
    }

    ResultActions getOrder() throws Exception {
        return mvc.perform(get(POS + "/orders/" + order)
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_VIEW)));
    }
}
