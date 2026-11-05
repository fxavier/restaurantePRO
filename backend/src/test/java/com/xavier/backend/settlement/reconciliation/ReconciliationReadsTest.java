package com.xavier.backend.settlement.reconciliation;

import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.BASE;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardMethod;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardPayment;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.confirmedAt;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.importCsv;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.json;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.line;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** PRD S31 reads: a store's batches, latest first with their counts; a batch's entries after its store (D42). */
@SettlementModuleTest
class ReconciliationReadsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aStoresBatchesAreListedLatestFirstWithTheirCounts() throws Exception {
        Sale sale = fixtures.sale("2");
        String card = cardMethod(fixtures);
        Instant at = confirmedAt(jdbc, cardPayment(fixtures, sale, card, "25.00", "A1"));
        String first = json(importCsv(mvc, sale.store(), card, line(at, "25.00", "0", null, "A1", null),
                UUID.randomUUID().toString()).andExpect(status().isCreated())).get("id").asString();
        String second = json(importCsv(mvc, sale.store(), card, line(at, "3.00", "0", null, "Z", null),
                UUID.randomUUID().toString()).andExpect(status().isCreated())).get("id").asString();

        mvc.perform(get(BASE + "/batches").param("storeId", sale.store().toString())
                        .with(CashFixtures.account(sale.store(), Permission.RECONCILIATION_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[0].entries.MISSING_INTERNAL").value(1))
                .andExpect(jsonPath("$.content[0].open").value(1))
                .andExpect(jsonPath("$.content[1].id").value(first))
                .andExpect(jsonPath("$.content[1].entries.MATCHED").value(1))
                .andExpect(jsonPath("$.content[1].open").value(0));
    }

    @Test
    void aBatchsEntriesAreAuthorizedOnItsStoreAndAnUnknownBatchIs404() throws Exception {
        Sale sale = fixtures.sale("2");
        String card = cardMethod(fixtures);
        String batch = json(importCsv(mvc, sale.store(), card, "", UUID.randomUUID().toString())
                .andExpect(status().isCreated())).get("id").asString();
        UUID elsewhere = UUID.randomUUID();

        mvc.perform(get(BASE + "/batches/" + batch + "/entries")
                        .with(CashFixtures.account(elsewhere, Permission.RECONCILIATION_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/batches/" + UUID.randomUUID() + "/entries")
                        .with(CashFixtures.account(elsewhere, Permission.RECONCILIATION_VIEW)))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/batches").param("storeId", sale.store().toString())
                        .with(CashFixtures.account(elsewhere, Permission.RECONCILIATION_VIEW)))
                .andExpect(status().isForbidden());
    }
}
