package com.xavier.backend.settlement.reconciliation;

import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardMethod;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardPayment;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.confirmedAt;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.entries;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.importCsv;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.json;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D78, PRD S31: a canonical CSV statement is imported once per method and file, and every line and every payment of
 * the period gets one entry: MATCHED (with the provider's fee, auto-resolved), AMOUNT_MISMATCH, MISSING_INTERNAL,
 * MISSING_PROVIDER or DUPLICATE_LINE. Payments count net of their refunds.
 */
@SettlementModuleTest
class StatementImportTest {

    static final UUID MANAGER = UUID.randomUUID();

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApproverVerifier approvers;

    Sale sale;
    String card;

    @BeforeEach
    void aSaleAndACardTerminal() throws Exception {
        sale = fixtures.sale("4"); // 50.00
        card = cardMethod(fixtures);
        when(approvers.verify(any(), any(), eq(Permission.PAYMENT_REFUND_APPROVE), any())).thenReturn(MANAGER);
    }

    @Test
    void aPaymentIsMatchedByItsAuthorizationCodeAndAmountWithTheFeeAndIsResolvedAutomatically() throws Exception {
        UUID payment = cardPayment(fixtures, sale, card, "25.00", "A100");
        Instant at = confirmedAt(jdbc, payment);

        JsonNode batch = json(importCsv(mvc, sale.store(), card,
                line(at.plus(Duration.ofHours(2)), "25.00", "0.75", "BANK-1", "A100", "L1"),
                UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("CSV"))
                .andExpect(jsonPath("$.lineCount").value(1))
                .andExpect(jsonPath("$.entries.MATCHED").value(1))
                .andExpect(jsonPath("$.open").value(0)));

        JsonNode entry = json(entries(mvc, sale.store(), id(batch), "").andExpect(status().isOk()))
                .get("content").get(0);
        assertThat(entry.get("kind").asString()).isEqualTo("MATCHED");
        assertThat(entry.get("paymentId").asString()).isEqualTo(payment.toString());
        assertThat(entry.get("lineNo").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(entry.get("fee").get("amount").asString())).isEqualByComparingTo("0.75");
        assertThat(new BigDecimal(entry.get("difference").get("amount").asString())).isEqualByComparingTo("0");
        assertThat(entry.get("resolution").get("action").asString()).isEqualTo("AUTO_MATCHED");
        assertThat(entry.get("resolution").get("actorKind").asString()).isEqualTo("SYSTEM");
    }

    @Test
    void everyOtherKindIsRecordedWithItsAmounts() throws Exception {
        UUID mismatched = cardPayment(fixtures, sale, card, "20.00", "A1");
        UUID missing = cardPayment(fixtures, sale, card, "10.00", "A2");
        Instant at = confirmedAt(jdbc, mismatched);

        JsonNode batch = json(importCsv(mvc, sale.store(), card,
                line(at, "19.50", "0", null, "A1", null) + line(at, "7.00", "0.10", null, "Z9", "L2"),
                UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries.AMOUNT_MISMATCH").value(1))
                .andExpect(jsonPath("$.entries.MISSING_INTERNAL").value(1))
                .andExpect(jsonPath("$.entries.MISSING_PROVIDER").value(1))
                .andExpect(jsonPath("$.open").value(3)));

        List<JsonNode> all = list(entries(mvc, sale.store(), id(batch), "?open=true"));
        assertThat(all).extracting(e -> e.get("kind").asString())
                .containsExactly("AMOUNT_MISMATCH", "MISSING_INTERNAL", "MISSING_PROVIDER");
        assertThat(all.get(0).get("paymentId").asString()).isEqualTo(mismatched.toString());
        assertThat(new BigDecimal(all.get(0).get("difference").get("amount").asString())).isEqualByComparingTo("-0.50");
        assertThat(all.get(1).get("paymentId").isNull()).isTrue();
        assertThat(new BigDecimal(all.get(1).get("difference").get("amount").asString())).isEqualByComparingTo("7.00");
        assertThat(all.get(2).get("paymentId").asString()).isEqualTo(missing.toString());
        assertThat(all.get(2).get("lineNo").isNull()).isTrue();
        assertThat(new BigDecimal(all.get(2).get("difference").get("amount").asString()))
                .isEqualByComparingTo("-10.00");
        assertThat(list(entries(mvc, sale.store(), id(batch), "?kind=MISSING_PROVIDER"))).hasSize(1);
    }

    @Test
    void aRefundedPaymentCountsNetOfItsRefunds() throws Exception {
        UUID payment = cardPayment(fixtures, sale, card, "25.00", "A7");
        fixtures.refund(sale.cashier(), payment, "5.00", "RF-1", UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        importCsv(mvc, sale.store(), card, line(confirmedAt(jdbc, payment), "20.00", "0", null, "A7", null),
                UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries.MATCHED").value(1));
    }

    @Test
    void aUniqueCandidateWithinTenMinutesIsMatchedWithoutAKey() throws Exception {
        UUID payment = cardPayment(fixtures, sale, card, "25.00", "A1");

        JsonNode batch = json(importCsv(mvc, sale.store(), card,
                line(confirmedAt(jdbc, payment).plus(Duration.ofMinutes(9)), "25.00", "0", null, null, null),
                UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries.MATCHED").value(1)));
        assertThat(list(entries(mvc, sale.store(), id(batch), "")).getFirst().get("paymentId").asString())
                .isEqualTo(payment.toString());
    }

    @Test
    void twoEqualCandidatesAreNeverPairedAtRandom() throws Exception {
        UUID first = cardPayment(fixtures, sale, card, "25.00", "A1");
        cardPayment(fixtures, sale, card, "25.00", "A2");

        importCsv(mvc, sale.store(), card, line(confirmedAt(jdbc, first), "25.00", "0", null, null, null),
                UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries.MATCHED").doesNotExist())
                .andExpect(jsonPath("$.entries.MISSING_INTERNAL").value(1))
                .andExpect(jsonPath("$.entries.MISSING_PROVIDER").value(2));
    }

    @Test
    void anIntegratedPaymentIsMatchedByItsProviderReference() throws Exception {
        String integrated = SettlementFixtures.freshCode("MPESA");
        fixtures.method(integrated, "INTEGRATED", FakePaymentProvider.CODE);
        UUID attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), integrated, "25.00", null,
                UUID.randomUUID().toString()).andExpect(status().isAccepted()));
        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("SUCCEEDED");
        UUID payment = fixtures.paymentOfAttempt(attempt);

        importCsv(mvc, sale.store(), integrated, line(confirmedAt(jdbc, payment).plus(Duration.ofHours(5)), "25.00",
                "0.30", "FAKE-" + attempt, null, null), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries.MATCHED").value(1));
    }

    @Test
    void theSameFileIsImportedOnce() throws Exception {
        UUID payment = cardPayment(fixtures, sale, card, "25.00", "A1");
        String csv = line(confirmedAt(jdbc, payment), "25.00", "0", null, "A1", null);
        UUID batch = id(json(importCsv(mvc, sale.store(), card, csv, UUID.randomUUID().toString())
                .andExpect(status().isCreated())));

        importCsv(mvc, sale.store(), card, csv, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATEMENT_ALREADY_IMPORTED"))
                .andExpect(jsonPath("$.batchId").value(batch.toString()));
        assertThat(fixtures.count("SELECT count(*) FROM settlement.reconciliation_batch WHERE store_id = ?",
                sale.store())).isEqualTo(1);
    }

    @Test
    void theSameKeyReplaysTheImport() throws Exception {
        UUID payment = cardPayment(fixtures, sale, card, "25.00", "A1");
        String csv = line(confirmedAt(jdbc, payment), "25.00", "0", null, "A1", null);
        String key = UUID.randomUUID().toString();
        UUID batch = id(json(importCsv(mvc, sale.store(), card, csv, key).andExpect(status().isCreated())));

        importCsv(mvc, sale.store(), card, csv, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(batch.toString()));
    }

    @Test
    void aLineAnEarlierBatchMatchedIsADuplicateAndItsPaymentIsNotMissing() throws Exception {
        UUID payment = cardPayment(fixtures, sale, card, "25.00", "A1");
        UUID other = cardPayment(fixtures, sale, card, "10.00", "A2");
        Instant at = confirmedAt(jdbc, payment);
        importCsv(mvc, sale.store(), card, line(at, "25.00", "0", null, "A1", "L1"), UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        JsonNode second = json(importCsv(mvc, sale.store(), card,
                line(at, "25.00", "0", null, "A1", "L1") + line(confirmedAt(jdbc, other), "10.00", "0", null, "A2",
                        "L2"), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries.DUPLICATE_LINE").value(1))
                .andExpect(jsonPath("$.entries.MATCHED").value(1))
                .andExpect(jsonPath("$.entries.MISSING_PROVIDER").doesNotExist()));
        JsonNode duplicate = list(entries(mvc, sale.store(), id(second), "?kind=DUPLICATE_LINE")).getFirst();
        assertThat(duplicate.get("paymentId").asString()).isEqualTo(payment.toString());
        assertThat(new BigDecimal(duplicate.get("difference").get("amount").asString()))
                .isEqualByComparingTo("25.00");
    }

    @Test
    void anInvalidStatementIsRefusedWithEveryErrorAndStoresNothing() throws Exception {
        importCsv(mvc, sale.store(), card, "not-a-date,25.00,0,,,\n" + line(Instant.now(), "-1", "0", null, null,
                        null), UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STATEMENT_INVALID"))
                .andExpect(jsonPath("$.errors[0].line").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("occurred_at"))
                .andExpect(jsonPath("$.errors[1].line").value(2))
                .andExpect(jsonPath("$.errors[1].field").value("amount"));
        assertThat(fixtures.count("SELECT count(*) FROM settlement.reconciliation_batch WHERE store_id = ?",
                sale.store())).isZero();
    }

    @Test
    void cashHasNoProviderStatement() throws Exception {
        importCsv(mvc, sale.store(), "CASH", "", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STATEMENT_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("methodCode"));
    }

    @Test
    void importingNeedsReconciliationManageInTheStore() throws Exception {
        importCsv(mvc, sale.store(), card, "", UUID.randomUUID().toString(), Permission.RECONCILIATION_VIEW)
                .andExpect(status().isForbidden());
    }

    static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }

    static List<JsonNode> list(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        List<JsonNode> content = new ArrayList<>();
        json(result.andExpect(status().isOk())).get("content").forEach(content::add);
        return content;
    }
}
