package com.xavier.backend.settlement.reconciliation;

import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.BASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D96 rule 6: a pending fiscal correction is listed until a CORRECTED resolution (with the corrective document's
 * reference) clears it, once, with {@code RECONCILIATION_MANAGE} in its store, audited; an ABSORBED cent needs none.
 * The scenario: 2 x 12.50 at 16 %, one unit in partition A (tax 1.73), the other voided (the line's tax is now 1.72),
 * A invoiced on its own: no residual document, so the close records a pending correction of A's document.
 */
@SettlementModuleTest
class TaxCorrectionResolutionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    JdbcClient jdbc;

    Sale sale;
    UUID documentA;
    UUID pending;

    @BeforeEach
    void aClosedOrderWithAPendingCorrection() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(UUID.randomUUID());
        sale = fixtures.sale("2");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        UUID a = SettlementFixtures.id(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1")
                .andExpect(status().isCreated()));
        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                "1", UUID.randomUUID().toString()).andExpect(status().isCreated());
        documentA = SettlementFixtures.id(fixtures.partitionDocument(sale.cashier(), a, UUID.randomUUID().toString())
                .andExpect(status().isCreated()));
        fixtures.payPartition(sale.cashier(), sale.order(), a, "CASH", "12.50", "12.50", UUID.randomUUID().toString())
                .andExpect(status().isCreated());
        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        pending = jdbc.sql("SELECT id FROM settlement.tax_rounding_adjustment WHERE order_id = ?")
                .param(sale.order()).query(UUID.class).single();
    }

    @Test
    void anOpenPendingCorrectionIsListedAndNamesItsDocument() throws Exception {
        JsonNode open = list("true");

        assertThat(open.get("content")).singleElement().satisfies(row -> {
            assertThat(row.get("id").asString()).isEqualTo(pending.toString());
            assertThat(row.get("orderId").asString()).isEqualTo(sale.order().toString());
            assertThat(row.get("documentId").asString()).isEqualTo(documentA.toString());
            assertThat(row.get("ratePercent").decimalValue()).isEqualByComparingTo("16");
            assertThat(row.get("amount").get("amount").decimalValue()).isEqualByComparingTo("-0.01");
            assertThat(row.get("resolution").isNull()).isTrue();
        });
        assertThat(control().get("exceptions")).singleElement().satisfies(e -> {
            assertThat(e.get("kind").asString()).isEqualTo("TAX_CORRECTION_PENDING");
            assertThat(e.get("documentId").asString()).isEqualTo(documentA.toString());
        });
    }

    @Test
    void aResolvedCorrectionIsExplainedWithItsReferenceAndAudited() throws Exception {
        resolve(pending, "NC A/12", "credit note issued by the adapter", Permission.RECONCILIATION_MANAGE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resolution.action").value("CORRECTED"))
                .andExpect(jsonPath("$.resolution.correctionReference").value("NC A/12"))
                .andExpect(jsonPath("$.resolution.actorKind").value("ACCOUNT"));

        JsonNode control = control();
        assertThat(control.get("exceptions")).isEmpty();
        assertThat(control.get("roundingDifferences")).singleElement().satisfies(r -> {
            assertThat(r.get("adjustmentId").asString()).isEqualTo(pending.toString());
            assertThat(r.get("correctionReference").asString()).isEqualTo("NC A/12");
            assertThat(r.get("difference").get("amount").decimalValue()).isEqualByComparingTo("-0.01");
        });
        assertThat(list("true").get("content")).isEmpty();
        assertThat(list("false").get("content")).hasSize(1);
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.taxRoundingDifference[0].corrected.amount").value(-0.01))
                .andExpect(jsonPath("$.taxRoundingDifference[0].pendingCorrection.amount").value(0));
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'TAX_CORRECTION_RESOLVED' "
                + "AND entity_id = ?", pending.toString())).isEqualTo(1);
    }

    @Test
    void aSecondResolutionIsAConflict() throws Exception {
        resolve(pending, "NC A/12", "issued", Permission.RECONCILIATION_MANAGE).andExpect(status().isCreated());

        resolve(pending, "NC A/13", "again", Permission.RECONCILIATION_MANAGE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TAX_CORRECTION_RESOLVED"));
    }

    @Test
    void anAbsorbedCentIsNotAPendingCorrection() throws Exception {
        UUID absorbed = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO settlement.tax_rounding_adjustment (id, order_id, store_id, business_date, source_line_id,
                    rate_percent, amount, currency, kind, document_id, target_line_id, target_partition_id,
                    actor_kind, actor_id, created_at)
                VALUES (?, ?, ?, DATE '2026-11-06', ?, 16, 0.01, 'MZN', 'ABSORBED', ?, ?, NULL, 'SYSTEM', NULL, now())""")
                .params(absorbed, UUID.randomUUID(), sale.store(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID()).update();

        resolve(absorbed, "NC 1", "none needed", Permission.RECONCILIATION_MANAGE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOT_A_PENDING_CORRECTION"));
    }

    @Test
    void aBlankReferenceOrReasonIsInvalid() throws Exception {
        resolve(pending, " ", "issued", Permission.RECONCILIATION_MANAGE).andExpect(status().isBadRequest());
        resolve(pending, "NC 1", " ", Permission.RECONCILIATION_MANAGE).andExpect(status().isBadRequest());
    }

    @Test
    void onlyReconciliationManageInTheAdjustmentsStoreResolves() throws Exception {
        resolve(pending, "NC 1", "issued", Permission.RECONCILIATION_VIEW).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/tax-corrections/" + pending + "/resolutions")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf())
                        .with(CashFixtures.account(UUID.randomUUID(), Permission.RECONCILIATION_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionReference\": \"NC 1\", \"reason\": \"issued\"}"))
                .andExpect(status().isForbidden());
        resolve(UUID.randomUUID(), "NC 1", "issued", Permission.RECONCILIATION_MANAGE)
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/tax-corrections").param("storeId", sale.store().toString())
                        .with(CashFixtures.account(sale.store(), Permission.PAYMENT_RESOLVE)))
                .andExpect(status().isForbidden());
    }

    private ResultActions resolve(UUID adjustment, String reference, String reason, Permission permission)
            throws Exception {
        return mvc.perform(post(BASE + "/tax-corrections/" + adjustment + "/resolutions")
                .header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf())
                .with(CashFixtures.account(sale.store(), permission)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"correctionReference\": \"%s\", \"reason\": \"%s\"}".formatted(reference, reason)));
    }

    private JsonNode list(String open) throws Exception {
        return SettlementFixtures.json(mvc.perform(get(BASE + "/tax-corrections")
                        .param("storeId", sale.store().toString()).param("open", open)
                        .with(CashFixtures.account(sale.store(), Permission.RECONCILIATION_VIEW)))
                .andExpect(status().isOk()));
    }

    private JsonNode control() throws Exception {
        LocalDate day = jdbc.sql("SELECT business_day FROM orders.order_header WHERE id = ?").param(sale.order())
                .query(LocalDate.class).single();
        JsonNode control = SettlementFixtures.json(mvc.perform(get(BASE + "/documents")
                        .param("storeId", sale.store().toString()).param("businessDate", day.toString())
                        .with(CashFixtures.account(sale.store(), Permission.RECONCILIATION_VIEW)))
                .andExpect(status().isOk()));
        return control;
    }
}
