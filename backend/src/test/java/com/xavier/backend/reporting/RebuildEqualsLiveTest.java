package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.CreditChargeFact;
import com.xavier.backend.settlement.SettlementFacts;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.model.event.CreditChargePosted;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * A rebuild pulls the owners' paged id listings through the same projection code as the live listeners (D84), so
 * a store's day rebuilt from empty equals its live projection, row for row and report for report (A28). One rebuild
 * runs at a time per store (409 {@code REBUILD_RUNNING}).
 */
@ReportingModuleTest
class RebuildEqualsLiveTest {

    static final Currency MZN = Currency.getInstance("MZN");

    /** Every projection row of a store, without the instant it was projected at. */
    static final List<String> PROJECTIONS = List.of(
            """
            SELECT document_id, business_date, local_date, local_hour, issued_at, order_id, terminal_id,
                   issued_by_employee_id, series_code, number, non_fiscal, total_gross, total_net, total_tax
            FROM reporting.sales_record WHERE store_id = ? ORDER BY document_id""",
            """
            SELECT document_id, line_no, item_id, item_code, family_code, description, quantity, gross, net, tax,
                   tax_code, tax_rate, served_by_employee_id, area_id, area_code, mode
            FROM reporting.sales_record_line WHERE store_id = ? ORDER BY document_id, line_no""",
            "SELECT document_id, tax_code, tax_rate, net, tax FROM reporting.sales_record_tax WHERE store_id = ? "
                    + "ORDER BY document_id, tax_rate",
            """
            SELECT source_type, source_id, business_date, local_hour, method_code, signed_amount, target_type,
                   order_id, employee_id, terminal_id
            FROM reporting.payment_record WHERE store_id = ? ORDER BY source_id""",
            "SELECT charge_id, business_date, customer_id, order_id, amount FROM reporting.credit_record "
                    + "WHERE store_id = ? ORDER BY charge_id",
            """
            SELECT void_id, business_date, local_hour, item_code, description, quantity, gross, reason, wasted,
                   after_dispatch, employee_id, approver_id
            FROM reporting.void_record WHERE store_id = ? ORDER BY void_id""");

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    SettlementFacts settlementFacts;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    com.xavier.backend.reporting.service.RebuildService rebuildService;

    @Autowired
    TransactionTemplate tx;

    Sale sale;
    LocalDate day;

    @BeforeEach
    void aLiveDayWithASaleAVoidAndACreditCharge() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(UUID.randomUUID());
        sale = sales.sale("3");
        UUID line = sales.lineIds(sale.cashier(), sale.order()).getFirst();
        sales.settledVoid(sale.cashier(), sale.order(), sales.orderVersion(sale.cashier(), sale.order()), line, "1",
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        sales.payAndClose(sale); // 2 × 12.50 left
        day = reporting.businessDayOf(sale.order());

        UUID charge = UUID.randomUUID();
        doReturn(Optional.of(new CreditChargeFact(charge, sale.store(), day, Instant.now(), Money.of("5.00", MZN),
                sale.order(), null, UUID.randomUUID(), UUID.randomUUID(), sale.cashier().employeeId(), null)))
                .when(settlementFacts).creditChargeFact(charge);
        doAnswer(call -> sale.store().equals(call.getArgument(0))
                ? new PageImpl<>(List.of(charge), call.getArgument(3), 1) : call.callRealMethod())
                .when(settlementFacts).creditChargeIds(any(), any(), any(), any(Pageable.class));
        tx.executeWithoutResult(status -> events.publishEvent(new CreditChargePosted(charge, sale.store())));

        reporting.awaitNothingPendingFor(sale.store());
        reporting.awaitSalesRecord(reporting.issuedDocument(sale.order()));
    }

    @Test
    void aDayRebuiltFromEmptyEqualsItsLiveProjection() throws Exception {
        List<List<Map<String, Object>>> live = projections();
        List<JsonNode> liveReports = reports();
        assertThat(live).allSatisfy(rows -> assertThat(rows).isNotEmpty());
        reporting.wipeProjections(sale.store());

        reporting.rebuild(sale.store(), day, day, UUID.randomUUID().toString())
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.storeId").value(sale.store().toString()));
        JsonNode run = reporting.awaitRebuildFinished(sale.store());

        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("projected").asLong()).isEqualTo(4); // the document, the void, the payment, the charge
        assertThat(projections()).isEqualTo(live);
        assertThat(reports()).isEqualTo(liveReports);
    }

    @Test
    void aRebuildOverLiveRowsLeavesThemAsTheyWere() throws Exception {
        List<List<Map<String, Object>>> live = projections();

        reporting.rebuild(sale.store(), day, day, UUID.randomUUID().toString()).andExpect(status().isAccepted());

        assertThat(reporting.awaitRebuildFinished(sale.store()).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(projections()).isEqualTo(live);
    }

    @Test
    void aSecondRebuildOfTheStoreWhileOneRunsIsRefused() throws Exception {
        UUID running = reporting.runningRebuild(sale.store(), day);
        try {
            reporting.rebuild(sale.store(), day, day, UUID.randomUUID().toString())
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REBUILD_RUNNING"));
        } finally {
            reporting.finishRebuild(running);
        }
    }

    @Test
    void aRunARestartInterruptedNoLongerBlocksTheStore() throws Exception {
        UUID interrupted = reporting.runningRebuild(sale.store(), day, "now() - interval '30 days'");

        rebuildService.abandonInterruptedRuns(); // what the application does when it is ready

        assertThat(reporting.row("SELECT status, error FROM reporting.rebuild_run WHERE id = ?", interrupted))
                .containsEntry("status", "FAILED").extractingByKey("error").isNotNull();
        reporting.rebuild(sale.store(), day, day, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        reporting.awaitRebuildFinished(sale.store());
    }

    @Test
    void aRunStartedSinceThisProcessStartedIsNotAbandoned() {
        UUID live = reporting.runningRebuild(sale.store(), day, "now()");
        try {
            rebuildService.abandonInterruptedRuns();

            assertThat(reporting.row("SELECT status FROM reporting.rebuild_run WHERE id = ?", live))
                    .containsEntry("status", "RUNNING");
        } finally {
            reporting.finishRebuild(live);
        }
    }

    @Test
    void aReplayedRebuildRequestStartsNoSecondRun() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = reporting.rebuild(sale.store(), day, day, key).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        reporting.awaitRebuildFinished(sale.store());

        String replay = reporting.rebuild(sale.store(), day, day, key).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(reporting.count("SELECT count(*) FROM reporting.rebuild_run WHERE store_id = ?", sale.store()))
                .isOne();
    }

    private List<List<Map<String, Object>>> projections() {
        return PROJECTIONS.stream().map(sql -> reporting.rows(sql, sale.store())).toList();
    }

    /** The reports of the day without their freshness ({@code asOf} changes with every projection). */
    private List<JsonNode> reports() throws Exception {
        List<JsonNode> reports = new java.util.ArrayList<>();
        for (String[] report : new String[][] {{"sales", "groupBy", "item"}, {"sales", "groupBy", "employee"},
                {"taxes"}, {"receipts", "groupBy", "day"}, {"voids", "groupBy", "reason"}}) {
            String[] extra = java.util.Arrays.copyOfRange(report, 1, report.length);
            JsonNode node = reporting.report(report[0], sale.store(), day, day, extra);
            reports.add(node.get("rows"));
            reports.add(node.get("totals"));
        }
        return reports;
    }
}
