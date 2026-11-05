package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * D55 and D50 §7.8: closing an order issues its sale document when it has none (gapless number), then checks under
 * the order's lock that nothing is in doubt, that paid = due and that the document is ISSUED, and closes the order at
 * the client's version (the table becomes FREE). A10: issuing a document creates no cash movement.
 */
@SettlementModuleTest
class OrderCloseTest {

    @Autowired
    SettlementFixtures fixtures;

    @Test
    void aPaidOrderClosesWithItsIssuedDocumentAndFreesTheTable() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "30.00").andExpect(status().isCreated());
        long version = fixtures.orderVersion(sale.cashier(), sale.order());

        fixtures.close(sale.cashier(), sale.order(), version, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CLOSED"))
                .andExpect(jsonPath("$.financialState").value("SETTLED"))
                .andExpect(jsonPath("$.fiscalState").value("ISSUED"))
                .andExpect(jsonPath("$.balance.amount").value(0.00));

        assertThat(fixtures.issuedNumber(sale.order())).isEqualTo(1);
        assertThat(fixtures.nextNumber(sale.series())).isEqualTo(2);
        assertThat(fixtures.closes(sale.order())).isEqualTo(1);
        assertThat(fixtures.tableFree(sale.order())).isTrue();
    }

    @Test
    void anUnpaidOrderDoesNotCloseAndNoDocumentIsIssuedForIt() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "10.00", "10.00").andExpect(status().isCreated());
        long version = fixtures.orderVersion(sale.cashier(), sale.order());

        fixtures.close(sale.cashier(), sale.order(), version, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOT_SETTLED"));

        assertThat(fixtures.documents(sale.order())).isZero();
        assertThat(fixtures.tableFree(sale.order())).isFalse();
    }

    @Test
    void aStaleOrderVersionIs409() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        long version = fixtures.orderVersion(sale.cashier(), sale.order());

        fixtures.close(sale.cashier(), sale.order(), version - 1, UUID.randomUUID().toString())
                .andExpect(status().isConflict());
        assertThat(fixtures.documents(sale.order())).isZero();
    }

    @Test
    void anAttemptStillOpenBlocksTheClose() throws Exception {
        Sale sale = fixtures.sale("2");
        String card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null);
        fixtures.pay(sale.cashier(), sale.order(), card, "25.00", null, UUID.randomUUID().toString())
                .andExpect(status().isCreated());
        long version = fixtures.orderVersion(sale.cashier(), sale.order());

        fixtures.close(sale.cashier(), sale.order(), version, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_DOUBT"));
    }

    @Test
    void aRepeatedCloseReplaysAndAClosedOrderAnswersWithItsView() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        long version = fixtures.orderVersion(sale.cashier(), sale.order());
        String key = UUID.randomUUID().toString();

        String first = fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay = fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        fixtures.close(sale.cashier(), sale.order(), 0, UUID.randomUUID().toString())
                .andExpect(status().isOk()).andExpect(jsonPath("$.lifecycle").value("CLOSED"));

        assertThat(SettlementFixtures.json(replay)).isEqualTo(SettlementFixtures.json(first));
        assertThat(fixtures.closes(sale.order())).isEqualTo(1);
        assertThat(fixtures.issuedNumber(sale.order())).isEqualTo(1);
    }

    @Test
    void issuingTheDocumentOfAnUnpaidOrderCreatesNoCashMovement() throws Exception {
        Sale sale = fixtures.sale("2");
        long movements = fixtures.movementsOfStore(sale.store());

        JsonNode document = SettlementFixtures.json(fixtures.issue(sale.cashier(), sale.order(),
                        UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.totalGross.amount").value(25.00)));

        assertThat(fixtures.movementsOfStore(sale.store())).isEqualTo(movements);
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.fiscalState").value("ISSUED"))
                .andExpect(jsonPath("$.documentId").value(document.get("id").asString()))
                .andExpect(jsonPath("$.financialState").value("UNSETTLED"));
    }

    @Test
    void anIssuedDocumentIsPaidAfterwardsAndTheOrderThenCloses() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(1));

        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        long version = fixtures.orderVersion(sale.cashier(), sale.order());
        fixtures.close(sale.cashier(), sale.order(), version, UUID.randomUUID().toString())
                .andExpect(status().isOk()).andExpect(jsonPath("$.lifecycle").value("CLOSED"));

        assertThat(fixtures.documents(sale.order())).isEqualTo(1);
        assertThat(fixtures.nextNumber(sale.series())).isEqualTo(2);
    }

    @Test
    void aFrozenOrderTakesNoNewRound() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString()).andExpect(status().isCreated());

        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = 'INVOICE'",
                sale.order())).isEqualTo(1);
    }
}
