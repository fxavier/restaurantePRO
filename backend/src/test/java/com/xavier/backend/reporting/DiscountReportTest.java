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

    @Autowired
    com.xavier.backend.orders.OrdersApi ordersApi;

    final UUID manager = UUID.randomUUID();
    LiveSale live;
    Pos cashier;
    String promotion;
    UUID promoted;
    long promotionVersion;
    LocalDate day;

    @BeforeEach
    void aDayOfAPromotionADiscountAndAnOffer() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_DISCOUNT_APPROVE), any())).thenReturn(manager);
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(manager);
        live = kitchen.liveSale("1"); // the round to offer: no promotion is published yet
        cashier = live.sale().cashier();
        UUID order = live.sale().order();
        UUID offered = sales.lineIds(cashier, order).getFirst();
        promotion = OrdersPromotions.code("R");
        promotions.promotion(live.store(), live.sale().shop().item(), promotion,
                "\"kind\": \"AMOUNT_OFF_UNIT\", \"amount\": 0.50", null);
        promotionVersion = promotions.publish(live.store());
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
        closeTheOrder(1);
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
        closeTheOrder(2);

        JsonNode report = reporting.report("discounts", live.store(), day, day, "groupBy", "origin");

        assertThat(rows(report)).as("1 of the 2 promoted (1.00 consumed): 0.50, not 0").contains(
                "OFFER|OFFER|2|2|24.00|1.00");
    }

    @Test
    void aReversedOfferNetsToNothingUnderOffer() throws Exception {
        UUID offered = sales.lineIds(cashier, live.sale().order()).getFirst();
        discount(offered, "\"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT, false).andExpect(status().isOk());
        kitchen.awaitRows("discount_record", live.store(), 4);
        reporting.awaitNothingPendingFor(live.store());
        closeTheOrder(0);

        JsonNode report = reporting.report("discounts", live.store(), day, day, "groupBy", "origin");

        assertThat(rows(report)).containsExactly(
                "MANUAL|MANUAL|1|2|1.00|0.00",
                "OFFER|OFFER|0|0|0.00|0.00",
                "PROMOTION|PROMOTION|1|2|1.00|0.00");
        assertThat(line(report.get("totals"))).isEqualTo("||2|4|2.00|0.00");
    }

    @Test
    void anOfferedUnitVoidedAfterTheOfferCarriesNoOfferCost() throws Exception {
        discount(promoted, "\"quantity\": 1}], \"kind\": \"OFFER\"", Permission.ORDER_OFFER, true, false)
                .andExpect(status().isOk());
        kitchen.voidLine(live.sale(), splitOf(promoted), "1", false).andExpect(status().isCreated());
        kitchen.awaitRows("discount_record", live.store(), 4);
        kitchen.awaitRows("void_record", live.store(), 1);
        reporting.awaitNothingPendingFor(live.store());
        closeTheOrder(1);

        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "origin")))
                .as("only the first offer's 0.50 is left").contains("OFFER|OFFER|2|2|24.00|0.50");
    }

    @Test
    void aVoidOfOtherUnitsOfTheRoundLineLeavesTheOfferCostAlone() throws Exception {
        discount(promoted, "\"quantity\": 1}], \"kind\": \"OFFER\"", Permission.ORDER_OFFER, true, false)
                .andExpect(status().isOk());
        kitchen.voidLine(live.sale(), promoted, "1", false).andExpect(status().isCreated()); // the unit not offered
        kitchen.awaitRows("discount_record", live.store(), 4);
        kitchen.awaitRows("consumption_record", live.store(), 3); // its consumption reversed
        reporting.awaitNothingPendingFor(live.store());
        closeTheOrder(2);

        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "origin")))
                .as("the offered unit still cost 0.50").contains("OFFER|OFFER|2|2|24.00|1.00");
    }

    @Test
    void aUnitVoidedBeforeTheOfferIsNotTakenOffIt() throws Exception {
        kitchen.voidLine(live.sale(), promoted, "1", false).andExpect(status().isCreated());
        discount(promoted, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, true).andExpect(status().isOk());
        kitchen.awaitRows("discount_record", live.store(), 4);
        kitchen.awaitRows("void_record", live.store(), 1);
        reporting.awaitNothingPendingFor(live.store());
        closeTheOrder(2);

        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "origin")))
                .as("the one unit left was offered: 0.50").contains("OFFER|OFFER|2|2|24.00|1.00");
    }

    @Test
    void aPartialReversalOfAnOfferLeavesTheRestOfferedAndNothingUnderPromotion() throws Exception {
        discount(promoted, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, true).andExpect(status().isOk());
        discount(promoted, "\"quantity\": 1}], \"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT, false,
                false).andExpect(status().isOk());
        kitchen.awaitRows("discount_record", live.store(), 5);
        closeTheOrder(2);

        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "origin")))
                .as("offered 1 + 2, 1 reversed: the cost of 2 offered coffees (0.50 each)").containsExactly(
                        "MANUAL|MANUAL|1|2|1.00|0.00",
                        "OFFER|OFFER|1|2|23.00|1.00",
                        "PROMOTION|PROMOTION|1|2|1.00|0.00");
    }

    @Test
    void anOfferedUnitTransferredAndVoidedOnTheTargetCostsNothing() throws Exception {
        UUID order = live.sale().order();
        UUID three = OrdersFixtures.id(orders.draft(live.sale().shop(), order, "3", cashier.employeeId(),
                cashier.terminalId()));
        orders.confirm(live.sale().shop(), order, """
                        {"orderVersion": %d, "catalogVersion": %d, "lineIds": ["%s"], "promotionVersion": %d}"""
                        .formatted(sales.orderVersion(cashier, order), live.sale().shop().catalogVersion(), three,
                                promotionVersion), cashier.employeeId(), cashier.terminalId())
                .andExpect(status().isCreated());
        kitchen.awaitRows("consumption_record", live.store(), 3);
        discount(three, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, true).andExpect(status().isOk());
        UUID target = orders.openedAt(live.sale().shop(), orders.newTable(live.sale().shop(), "T"
                + UUID.randomUUID().toString().substring(0, 5)), cashier.employeeId(), cashier.terminalId());
        orders.transfer(live.sale().shop(), order, sales.orderVersion(cashier, order), target,
                sales.orderVersion(cashier, target), java.util.Map.of(three, "1"), false, cashier.employeeId(),
                cashier.terminalId(), UUID.randomUUID().toString()).andExpect(status().isCreated());
        UUID copy = ordersApi.billingView(target).lines().getFirst().lineId();
        sales.settledVoid(cashier, target, sales.orderVersion(cashier, target), copy, "1",
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        closeTheOrder(2);

        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "origin")))
                .as("the first offer's 0.50 and 2 of the 3 (2c = 1.00)").contains("OFFER|OFFER|2|4|48.50|1.50");
    }

    @Test
    void anOfferReversedThenVoidedCostsNothingAndNeverLess() throws Exception {
        UUID offered = sales.lineIds(cashier, live.sale().order()).getFirst();
        discount(offered, "\"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT, false).andExpect(status().isOk());
        kitchen.voidLine(live.sale(), offered, "1", false).andExpect(status().isCreated());
        kitchen.awaitRows("discount_record", live.store(), 4);
        closeTheOrder(0);

        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "origin")))
                .contains("OFFER|OFFER|0|0|0.00|0.00");
    }

    @Test
    void anOfferReversedReOfferedAndPartlyVoidedIsCostedOnce() throws Exception {
        discount(promoted, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, true).andExpect(status().isOk());
        discount(promoted, "\"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT, false).andExpect(status().isOk());
        discount(promoted, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, true).andExpect(status().isOk());
        kitchen.voidLine(live.sale(), promoted, "1", false).andExpect(status().isCreated());
        kitchen.awaitRows("discount_record", live.store(), 6);
        closeTheOrder(2);

        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "origin")))
                .as("the first offer's 0.50 and the one coffee still offered").contains(
                        "OFFER|OFFER|2|3|35.50|1.00");
    }

    @Test
    void anOfferReversedToAPositivePriceIsADiscountedLineWithNoOfferCost() throws Exception {
        UUID offered = sales.lineIds(cashier, live.sale().order()).getFirst();
        discount(offered, "\"kind\": \"REVERSAL\", \"unitPrice\": 5.00", Permission.ORDER_DISCOUNT, false)
                .andExpect(status().isOk());
        kitchen.awaitRows("discount_record", live.store(), 4);
        closeTheOrder(0);

        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "origin")))
                .as("an offer is a free unit: at 5.00 it is a discount, its amounts as recorded")
                .contains("OFFER|OFFER|0|0|7.50|0.00");
    }

    @Test
    void offerThreeReverseAllVoidOneOfferTheTwoLeftCostsTwoUnits() throws Exception {
        UUID three = confirmedRoundOf("3");
        discount(three, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, true).andExpect(status().isOk()); // t1
        discount(three, "\"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT, false)
                .andExpect(status().isOk()); // t2
        kitchen.voidLine(live.sale(), three, "1", false).andExpect(status().isCreated()); // t3, at list price
        discount(three, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, true).andExpect(status().isOk()); // t4
        // the 3 of the day, the round's promotion, the offer t1, the reversal t2 and the offer t4 (the void adds none)
        kitchen.awaitRows("discount_record", live.store(), 7);
        closeTheOrder(2);

        assertThat(rows(reporting.report("discounts", live.store(), day, day, "groupBy", "origin")))
                .as("the first offer's 0.50 and the 2 offered at t4 (2c = 1.00)")
                .contains("OFFER|OFFER|2|3|36.00|1.50");
    }

    /** A round of {@code quantity} under the published promotion (12.00 each), consumed. @return its line */
    private UUID confirmedRoundOf(String quantity) throws Exception {
        UUID order = live.sale().order();
        UUID line = OrdersFixtures.id(orders.draft(live.sale().shop(), order, quantity, cashier.employeeId(),
                cashier.terminalId()));
        orders.confirm(live.sale().shop(), order, """
                        {"orderVersion": %d, "catalogVersion": %d, "lineIds": ["%s"], "promotionVersion": %d}"""
                        .formatted(sales.orderVersion(cashier, order), live.sale().shop().catalogVersion(), line,
                                promotionVersion), cashier.employeeId(), cashier.terminalId())
                .andExpect(status().isCreated());
        kitchen.awaitRows("consumption_record", live.store(), 3);
        return line;
    }

    /** Pays what is due in cash and closes the order; waits until the cost of its offers is projected. */
    private void closeTheOrder(int offeredLines) throws Exception {
        UUID order = live.sale().order();
        java.math.BigDecimal due = ordersApi.billingView(order).due().amount();
        if (due.signum() > 0) {
            sales.pay(cashier, order, "CASH", due.toPlainString(), due.toPlainString(), UUID.randomUUID().toString())
                    .andExpect(status().isCreated());
        }
        sales.close(cashier, order, sales.orderVersion(cashier, order), UUID.randomUUID().toString())
                .andExpect(status().isOk());
        reporting.awaitNothingPendingFor(live.store());
        kitchen.awaitRows("offer_cost_record", live.store(), offeredLines);
    }

    private UUID splitOf(UUID line) {
        return (UUID) reporting.row("SELECT target_line_id FROM orders.order_line_split WHERE source_line_id = ?",
                line).get("target_line_id");
    }

    @Test
    void byPromotionApproverEmployeeAndItem() throws Exception {
        closeTheOrder(1);
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
        closeTheOrder(1);
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
