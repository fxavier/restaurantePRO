package com.xavier.backend.settlement.reconciliation;

import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.BASE;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardMethod;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardPayment;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.confirmedAt;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.entries;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.importCsv;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.json;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.line;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.resolve;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D78: a person resolves an open entry once (ACCEPTED, REFUNDED_AT_PROVIDER or ESCALATED, with a reason), audited; a
 * MATCHED entry is already AUTO_MATCHED; the entry's store is read first and authorized (D42).
 */
@SettlementModuleTest
class EntryResolutionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    Sale sale;
    Map<String, UUID> byKind;

    @BeforeEach
    void aBatchWithAMatchAndAMismatch() throws Exception {
        sale = fixtures.sale("4");
        String card = cardMethod(fixtures);
        UUID matched = cardPayment(fixtures, sale, card, "20.00", "A1");
        cardPayment(fixtures, sale, card, "10.00", "A2");
        Instant at = confirmedAt(jdbc, matched);
        UUID batch = UUID.fromString(json(importCsv(mvc, sale.store(), card,
                line(at, "20.00", "0", null, "A1", null) + line(at, "9.00", "0", null, "A2", null),
                UUID.randomUUID().toString()).andExpect(status().isCreated())).get("id").asString());
        byKind = new HashMap<>();
        for (JsonNode entry : json(entries(mvc, sale.store(), batch, "").andExpect(status().isOk())).get("content")) {
            byKind.put(entry.get("kind").asString(), UUID.fromString(entry.get("id").asString()));
        }
    }

    @Test
    void anOpenEntryIsResolvedOnceAndAudited() throws Exception {
        UUID entry = byKind.get("AMOUNT_MISMATCH");

        resolve(mvc, sale.store(), entry, "ACCEPTED", "the acquirer kept a surcharge")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.action").value("ACCEPTED"))
                .andExpect(jsonPath("$.actorKind").value("ACCOUNT"))
                .andExpect(jsonPath("$.actorId").value(CashFixtures.ACCOUNT_ID.toString()));

        resolve(mvc, sale.store(), entry, "ESCALATED", "again")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_RESOLVED"));
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = "
                + "'RECONCILIATION_ENTRY_RESOLVED' AND entity_id = ?", entry.toString())).isEqualTo(1);
    }

    @Test
    void aMatchedEntryIsAlreadyResolvedAutomatically() throws Exception {
        resolve(mvc, sale.store(), byKind.get("MATCHED"), "ACCEPTED", "fine")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_RESOLVED"));
    }

    @Test
    void autoMatchedAndAMissingReasonAreRefusedAsInvalid() throws Exception {
        resolve(mvc, sale.store(), byKind.get("AMOUNT_MISMATCH"), "AUTO_MATCHED", "mine")
                .andExpect(status().isBadRequest());
        resolve(mvc, sale.store(), byKind.get("AMOUNT_MISMATCH"), "ACCEPTED", " ")
                .andExpect(status().isBadRequest());
    }

    @Test
    void theEntrysStoreIsAuthorizedAfterReadingItAlone() throws Exception {
        UUID entry = byKind.get("AMOUNT_MISMATCH");
        mvc.perform(post(BASE + "/entries/" + entry + "/resolutions")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf())
                        .with(CashFixtures.account(UUID.randomUUID(), Permission.RECONCILIATION_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\": \"ACCEPTED\", \"reason\": \"x\"}"))
                .andExpect(status().isForbidden());
        resolve(mvc, sale.store(), UUID.randomUUID(), "ACCEPTED", "x").andExpect(status().isNotFound());
    }
}
