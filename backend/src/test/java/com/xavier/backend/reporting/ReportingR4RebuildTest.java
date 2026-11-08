package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.reporting.CostKitchen.LiveSale;
import com.xavier.backend.settlement.SettlementFixtures;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D84, D131, A52: a day's cost of goods and discounts rebuilt from empty equal their live projection, row for row and
 * report for report. The day has every R4 source: a consumed round, a void with waste (no stock movement: found
 * through {@code InventoryFacts.wasteVoidIds}), a void without, a manual discount, a manual waste, a linked return
 * with a cost variance, and a correction that revalued nothing (a variance with no movement: found through
 * {@code InventoryFacts.costVarianceSources}, not {@code sourceIds}).
 */
@ReportingModuleTest
class ReportingR4RebuildTest {

    /** Every R4 projection row of a store, without the instant it was projected at. */
    static final List<String> PROJECTIONS = List.of(
            """
            SELECT movement_id, business_date, sale_item_id, family_code, line_id, warehouse_id, stock_item_id, type,
                   quantity, value, source_type, source_id
            FROM reporting.consumption_record WHERE store_id = ? ORDER BY movement_id""",
            """
            SELECT void_id, original_movement_id, business_date, sale_item_id, family_code, line_id, warehouse_id,
                   stock_item_id, quantity, value
            FROM reporting.consumption_waste_record WHERE store_id = ? ORDER BY void_id, original_movement_id""",
            """
            SELECT variance_id, source_type, source_id, business_date, warehouse_id, stock_item_id, receipt_line_id,
                   value
            FROM reporting.cost_variance_record WHERE store_id = ? ORDER BY variance_id""",
            """
            SELECT source_type, source_id, line_id, business_date, item_id, family_code, origin, kind, promotion_code,
                   operator_employee_id, approver_employee_id, quantity, discount_gross
            FROM reporting.discount_record WHERE store_id = ? ORDER BY source_id, line_id""",
            """
            SELECT order_id, line_id, business_date, item_id, family_code, promotion_code, operator_employee_id,
                   approver_employee_id, net_quantity, consumed_line_id, consumed_line_quantity
            FROM reporting.offer_cost_record WHERE store_id = ? ORDER BY order_id, line_id""");

    @Autowired
    CostKitchen kitchen;

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    MockMvc mvc;

    LiveSale live;
    LocalDate day;

    @BeforeEach
    void aLiveDayWithEveryCostOfGoodsSource() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(UUID.randomUUID());
        when(approvers.verify(any(), any(), eq(Permission.ORDER_DISCOUNT_APPROVE), any()))
                .thenReturn(UUID.randomUUID());
        live = kitchen.liveSale("4");
        UUID store = live.store();
        Pos cashier = live.sale().cashier();
        kitchen.awaitRows("consumption_record", store, 1);
        UUID line = sales.lineIds(cashier, live.sale().order()).getFirst();
        kitchen.voidLine(live.sale(), line, "1", true).andExpect(status().isCreated());
        kitchen.voidLine(live.sale(), line, "1", false).andExpect(status().isCreated());
        mvc.perform(post(OrdersFixtures.POS + "/orders/" + live.sale().order() + "/discounts")
                        .with(OrdersActors.pos(store, cashier.employeeId(), cashier.terminalId(),
                                Permission.ORDER_DISCOUNT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Approver-Code", "MGR").header("X-Approver-Pin", "1234")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderVersion": %d, "lines": [{"lineId": "%s"}], "kind": "DISCOUNT",
                                 "unitPrice": 11.50, "reason": "regular"}"""
                                .formatted(sales.orderVersion(cashier, live.sale().order()), line)))
                .andExpect(status().isOk());
        // a round of 2 offered, then 1 of them reversed: one coffee offered at close (0.50)
        sales.confirmRound(live.sale().shop(), cashier, live.sale().order(), "2",
                sales.orderVersion(cashier, live.sale().order()));
        UUID offered = sales.lineIds(cashier, live.sale().order()).getLast();
        adjust(cashier, offered, null, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER);
        adjust(cashier, offered, "1", "\"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT);
        sales.payCash(live.sale(), "35.50", "35.50").andExpect(status().isCreated());
        sales.close(cashier, live.sale().order(), sales.orderVersion(cashier, live.sale().order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        kitchen.wasteFlour(live, "1");
        kitchen.linkedReturnWithVariance(live);
        kitchen.correctionWithNothingOnHand(live);
        reporting.issuedDocuments(live.sale().order()).forEach(reporting::awaitSalesRecord);
        kitchen.awaitRows("consumption_record", store, 5); // 2 rounds, the reversal, the flour and the salt waste
        kitchen.awaitRows("consumption_waste_record", store, 1);
        kitchen.awaitRows("cost_variance_record", store, 2);
        kitchen.awaitRows("discount_record", store, 3);
        kitchen.awaitRows("offer_cost_record", store, 1);
        reporting.awaitNothingPendingFor(store);
        day = reporting.businessDayOf(live.sale().order());
    }

    @Test
    void aDayRebuiltFromEmptyEqualsItsLiveCostOfGoodsAndDiscounts() throws Exception {
        List<List<Map<String, Object>>> live = projections();
        List<JsonNode> liveReports = reports();
        assertThat(live).allSatisfy(rows -> assertThat(rows).isNotEmpty());
        assertThat(liveReports.getFirst().get("totals").get("costVariance").get("amount").decimalValue())
                .as("the return's −5.00 and the correction's 10.00").isEqualByComparingTo("5.00");
        reporting.wipeProjections(this.live.store());
        assertThat(projections()).allSatisfy(rows -> assertThat(rows).isEmpty());

        reporting.rebuild(this.live.store(), day, day, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        JsonNode run = reporting.awaitRebuildFinished(this.live.store());

        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(projections()).isEqualTo(live);
        assertThat(reports()).isEqualTo(liveReports);
    }

    @Test
    void aRebuildClearsTheRangesRowsBeforePullingTheFacts() throws Exception {
        List<List<Map<String, Object>>> live = projections();
        UUID stray = UUID.randomUUID();
        reporting.rows("""
                INSERT INTO reporting.consumption_record (movement_id, store_id, business_date, warehouse_id,
                    stock_item_id, type, quantity, value, source_type, source_id, projected_at)
                VALUES (?, ?, ?, ?, ?, 'WASTE', -1, -1, 'MANUAL_MOVEMENT', ?, now()) RETURNING movement_id""",
                stray, this.live.store(), day, this.live.warehouse(), this.live.flour(), stray);
        reporting.rows("""
                INSERT INTO reporting.consumption_waste_record (void_id, original_movement_id, store_id,
                    business_date, line_id, warehouse_id, stock_item_id, quantity, value, projected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, now()) RETURNING void_id""",
                stray, stray, this.live.store(), day, stray, this.live.warehouse(), this.live.flour());
        reporting.rows("""
                INSERT INTO reporting.cost_variance_record (variance_id, source_type, source_id, store_id,
                    business_date, warehouse_id, stock_item_id, value, projected_at)
                VALUES (?, 'COST_CORRECTION', ?, ?, ?, ?, ?, 1, now()) RETURNING variance_id""",
                stray, stray, this.live.store(), day, this.live.warehouse(), this.live.flour());
        reporting.rows("""
                INSERT INTO reporting.discount_record (source_type, source_id, line_id, store_id, business_date,
                    origin, kind, quantity, discount_gross, projected_at)
                VALUES ('ADJUSTMENT', ?, ?, ?, ?, 'MANUAL', 'DISCOUNT', 1, 1, now()) RETURNING line_id""",
                stray, stray, this.live.store(), day);

        reporting.rebuild(this.live.store(), day, day, UUID.randomUUID().toString()).andExpect(status().isAccepted());

        assertThat(reporting.awaitRebuildFinished(this.live.store()).get("status").asString())
                .isEqualTo("COMPLETED");
        assertThat(projections()).isEqualTo(live);
    }

    private void adjust(Pos cashier, UUID line, String quantity, String values, Permission permission)
            throws Exception {
        String target = quantity == null ? "{\"lineId\": \"%s\"}".formatted(line)
                : "{\"lineId\": \"%s\", \"quantity\": %s}".formatted(line, quantity);
        mvc.perform(post(OrdersFixtures.POS + "/orders/" + this.live.sale().order() + "/discounts")
                        .with(OrdersActors.pos(this.live.store(), cashier.employeeId(), cashier.terminalId(),
                                permission)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Approver-Code", "MGR").header("X-Approver-Pin", "1234")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderVersion": %d, "lines": [%s], %s, "reason": "regular"}"""
                                .formatted(sales.orderVersion(cashier, this.live.sale().order()), target, values)))
                .andExpect(status().isOk());
    }

    private List<List<Map<String, Object>>> projections() {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        PROJECTIONS.forEach(sql -> rows.add(reporting.rows(sql, live.store())));
        return rows;
    }

    /** The margin and discount reports of the day, without their freshness (asOf moves with a rebuild). */
    private List<JsonNode> reports() throws Exception {
        List<JsonNode> reports = new ArrayList<>();
        for (String groupBy : List.of("day", "item", "family")) {
            reports.add(withoutFreshness(reporting.report("margins", live.store(), day, day, "groupBy", groupBy)));
        }
        for (String groupBy : List.of("origin", "employee", "item")) {
            reports.add(withoutFreshness(reporting.report("discounts", live.store(), day, day, "groupBy", groupBy)));
        }
        return reports;
    }

    private static JsonNode withoutFreshness(JsonNode report) {
        ((tools.jackson.databind.node.ObjectNode) report).remove(List.of("asOf", "pendingFacts", "final"));
        return report;
    }
}
