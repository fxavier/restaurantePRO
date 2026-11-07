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
import com.xavier.backend.orders.OrdersPromotions;
import com.xavier.backend.reporting.CostKitchen.LiveSale;
import com.xavier.backend.reporting.service.CostProjections;
import com.xavier.backend.settlement.SettlementFixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D131, §8.6, A52: discounts and offers by origin, promotion, employee, approver and item, with the cost of offers
 * (the consumption cost of the offered lines). The day, in a store live in inventory (0.50 of flour an item): a round
 * of 2 under a promotion of 0.50 off a unit (1.00), a manual discount of that line from 12.00 to 11.50 (1.00, by the
 * cashier alone) and a round of 1 without the promotion, offered with a manager's approval (12.50, costing 0.50).
 */
@ReportingModuleTest
class DiscountReportTest {

    @Autowired
    CostKitchen kitchen;

    @Autowired
    OrdersFixtures orders;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    MockMvc mvc;

    @Autowired
    CostProjections projections;

    final UUID manager = UUID.randomUUID();
    LiveSale live;
    Pos cashier;
    String promotion;
    UUID promoted;
    LocalDate day;

    @BeforeEach
    void aDayOfAPromotionADiscountAndAnOffer() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_DISCOUNT_APPROVE), any())).thenReturn(manager);
        live = kitchen.liveSale("1"); // the round to offer: no promotion is published yet
        cashier = live.sale().cashier();
        UUID order = live.sale().order();
        UUID offered = sales.lineIds(cashier, order).getFirst();
        promotion = OrdersPromotions.code("R");
        promotions.promotion(live.store(), live.sale().shop().item(), promotion,
                "\"kind\": \"AMOUNT_OFF_UNIT\", \"amount\": 0.50", null);
        long promotionVersion = promotions.publish(live.store());
        promoted = OrdersFixtures.id(orders.draft(live.sale().shop(), order, "2", cashier.employeeId(),
                cashier.terminalId()));
        orders.confirm(live.sale().shop(), order, """
                        {"orderVersion": %d, "catalogVersion": %d, "lineIds": ["%s"], "promotionVersion": %d}"""
                        .formatted(sales.orderVersion(cashier, order), live.sale().shop().catalogVersion(), promoted,
                                promotionVersion), cashier.employeeId(), cashier.terminalId())
                .andExpect(status().isCreated());
        discount(promoted, "\"kind\": \"DISCOUNT\", \"unitPrice\": 11.50", Permission.ORDER_DISCOUNT, false)
                .andExpect(status().isOk());
        discount(offered, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, true).andExpect(status().isOk());
        kitchen.awaitRows("discount_record", live.store(), 3);
        kitchen.awaitRows("consumption_record", live.store(), 2);
        reporting.awaitNothingPendingFor(live.store());
        day = reporting.businessDayOf(order);
    }

    @Test
    void byOriginTheOfferCarriesTheCostOfWhatWasGivenAway() throws Exception {
        JsonNode report = reporting.report("discounts", live.store(), day, day, "groupBy", "origin");

        assertThat(rows(report)).containsExactly(
                "MANUAL|MANUAL|1|2|1.00|0.00",
                "OFFER|OFFER|1|1|12.50|0.50",
                "PROMOTION|PROMOTION|1|2|1.00|0.00");
        assertThat(line(report.get("totals"))).isEqualTo("||3|5|14.50|0.50");
        assertThat(report.get("asOf").isNull()).isFalse();
    }

    @Test
    void anOfferOfPartOfALineCostsItsShareOfTheRoundLinesConsumption() throws Exception {
        discount(promoted, "\"quantity\": 1}], \"kind\": \"OFFER\"", Permission.ORDER_OFFER, true, false)
                .andExpect(status().isOk());
        kitchen.awaitRows("discount_record", live.store(), 4);
        reporting.awaitNothingPendingFor(live.store());

        JsonNode report = reporting.report("discounts", live.store(), day, day, "groupBy", "origin");

        assertThat(rows(report)).as("1 of the 2 promoted (1.00 consumed): 0.50, not 0").contains(
                "OFFER|OFFER|2|2|24.00|1.00");
    }

    @Test
    void byPromotionApproverEmployeeAndItem() throws Exception {
        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "promotion")))
                .containsExactly(promotion + "|" + promotion + "|1|2|1.00|0.00", "||2|3|13.50|0.50");
        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "approver")))
                .as("only the offer needed an approver").containsExactly(
                        manager + "|" + manager + "|1|1|12.50|0.50", "||2|4|2.00|0.00");
        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "employee")))
                .containsExactly(cashier.employeeId() + "|" + cashier.employeeId() + "|3|5|14.50|0.50");
        JsonNode byItem = reporting.report("discounts", live.store(), day, day, "groupBy", "item");
        assertThat(byItem.get("rows")).hasSize(1);
        assertThat(byItem.get("rows").get(0).get("key").asString()).isEqualTo(live.sale().shop().item().toString());
        assertThat(byItem.get("rows").get(0).get("label").asString()).isNotBlank()
                .isNotEqualTo(live.sale().shop().item().toString());
    }

    @Test
    void aRedeliveredDiscountSourceProjectsNothingNew() throws Exception {
        List<java.util.Map<String, Object>> before = reporting.rows(
                "SELECT * FROM reporting.discount_record WHERE store_id = ? ORDER BY line_id, source_id",
                live.store());
        for (java.util.Map<String, Object> row : before) {
            projections.projectDiscounts((String) row.get("source_type"), (UUID) row.get("source_id"));
        }

        assertThat(reporting.rows("SELECT * FROM reporting.discount_record WHERE store_id = ? ORDER BY line_id, "
                + "source_id", live.store())).isEqualTo(before);
    }

    @Test
    void theDiscountsAreExportedAsCsvAndAudited() throws Exception {
        String csv = mvc.perform(post(ReportingFixtures.REPORTS + "/exports")
                        .with(com.xavier.backend.cash.CashFixtures.account(live.store(), Permission.REPORT_VIEW,
                                Permission.REPORT_EXPORT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"report": "discounts", "params": {"storeId": "%s", "from": "%s", "to": "%s",
                                 "groupBy": "origin"}}""".formatted(live.store(), day, day)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(csv.split("\r\n")).containsExactly(
                "key,label,lines,quantity,discount,offerCost",
                "MANUAL,MANUAL,1,2.000,1.00,0.00",
                "OFFER,OFFER,1,1.000,12.50,0.50",
                "PROMOTION,PROMOTION,1,2.000,1.00,0.00",
                "TOTAL,,3,5.000,14.50,0.50");
        assertThat(reporting.count("SELECT count(*) FROM audit.audit_event WHERE action = 'REPORT_EXPORTED' "
                + "AND store_id = ? AND details->>'report' = 'discounts'", live.store())).isOne();
    }

    private ResultActions discount(UUID line, String values, Permission permission, boolean approved)
            throws Exception {
        return discount(line, "}], " + values, permission, approved, true);
    }

    /** {@code values} continues the line object ({@code "quantity": 1}], …) when {@code wholeLine} is false. */
    private ResultActions discount(UUID line, String values, Permission permission, boolean approved,
                                   boolean wholeLine) throws Exception {
        var request = post(OrdersFixtures.POS + "/orders/" + live.sale().order() + "/discounts")
                .with(OrdersActors.pos(live.store(), cashier.employeeId(), cashier.terminalId(), permission))
                .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderVersion": %d, "lines": [{"lineId": "%s"%s, "reason": "regular"}"""
                        .formatted(sales.orderVersion(cashier, live.sale().order()), line,
                                wholeLine ? values : ", " + values));
        if (approved) {
            request = request.header("X-Approver-Code", "MGR").header("X-Approver-Pin", "1234");
        }
        return mvc.perform(request);
    }

    /** {@code key|label|lines|quantity|discount|offerCost} of each row. */
    private static List<String> rows(JsonNode report) {
        List<String> rows = new java.util.ArrayList<>();
        report.get("rows").forEach(row -> rows.add(line(row)));
        return rows;
    }

    private static String line(JsonNode row) {
        return text(row.get("key")) + "|" + text(row.get("label")) + "|" + row.get("lines").asLong() + "|"
                + row.get("quantity").decimalValue().stripTrailingZeros().toPlainString() + "|"
                + row.get("discount").get("amount").decimalValue().setScale(2).toPlainString() + "|"
                + row.get("offerCost").get("amount").decimalValue().setScale(2).toPlainString();
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString();
    }
}
