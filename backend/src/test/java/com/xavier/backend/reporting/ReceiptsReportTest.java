package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.settlement.CreditChargeFact;
import com.xavier.backend.settlement.RefundFact;
import com.xavier.backend.settlement.SettlementFacts;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.model.event.CreditChargePosted;
import com.xavier.backend.settlement.model.event.RefundRecorded;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Receipts are the money that went through the till (D87, A28): per method they equal the store's PAYMENT_RECEIPT −
 * REFUND movements; credit is a separate column and never money received (spec §14 #4). Refund and credit-charge
 * facts are stubbed on {@link SettlementFacts} until S29/S30 record them (PRD S32); S33 proves them end to end.
 */
@ReportingModuleTest
class ReceiptsReportTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    SettlementFacts settlementFacts;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    String card;
    Sale sale;
    LocalDate day;

    @BeforeEach
    void aSalePaidInCashAndByCard() throws Exception {
        card = SettlementFixtures.freshCode("CARD");
        sales.method(card, "EXTERNAL_TERMINAL", null);
        sale = sales.sale("2"); // 25.00
        sales.payCash(sale, "10.00", "10.00").andExpect(status().isCreated());
        reporting.payByCard(sale, card, "15.00");
        reporting.payments(sale.order()).forEach(reporting::awaitPaymentRecord);
        day = reporting.businessDayOf(sale.order());
    }

    @Test
    void receiptsByMethodEqualTheCashMovements() throws Exception {
        JsonNode report = reporting.report("receipts", sale.store(), day, day, "groupBy", "method");

        Map<String, BigDecimal> received = new TreeMap<>();
        report.get("rows").forEach(row -> received.put(row.get("key").asString(),
                row.get("received").get("amount").decimalValue().setScale(2)));
        assertThat(received).isEqualTo(reporting.movementsByMethod(sale.store()))
                .containsEntry("CASH", new BigDecimal("10.00")).containsEntry(card, new BigDecimal("15.00"));
        assertThat(amount(report.get("totals"), "received")).isEqualByComparingTo("25.00");
        assertThat(amount(report.get("totals"), "onAccount")).isEqualByComparingTo("0");
    }

    @Test
    void aRefundIsReceivedNegativelyInItsMethod() throws Exception {
        UUID refund = UUID.randomUUID();
        doReturn(java.util.Optional.of(new RefundFact(refund, reporting.payments(sale.order()).getFirst(),
                sale.store(), day, java.time.Instant.now(), "CASH", Money.of("4.00", MZN), sale.order(), null, null,
                sale.cashier().employeeId(), UUID.randomUUID(), sale.cashier().terminalId())))
                .when(settlementFacts).refundFact(refund);

        tx.executeWithoutResult(status -> events.publishEvent(new RefundRecorded(refund, sale.store())));
        reporting.awaitNothingPendingFor(sale.store());

        JsonNode report = reporting.report("receipts", sale.store(), day, day, "groupBy", "method");
        assertThat(report.get("rows").valueStream().filter(row -> "CASH".equals(row.get("key").asString()))
                .map(row -> amount(row, "received")).findFirst().orElseThrow()).isEqualByComparingTo("6.00");
        assertThat(amount(report.get("totals"), "received")).isEqualByComparingTo("21.00");
    }

    @Test
    void creditIsASeparateColumnAndNeverMoneyReceived() throws Exception {
        UUID charge = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        doReturn(java.util.Optional.of(new CreditChargeFact(charge, sale.store(), day, java.time.Instant.now(),
                Money.of("7.50", MZN), sale.order(), null, customer, UUID.randomUUID(), sale.cashier().employeeId(),
                sale.cashier().terminalId()))).when(settlementFacts).creditChargeFact(charge);

        tx.executeWithoutResult(status -> events.publishEvent(new CreditChargePosted(charge, sale.store())));
        reporting.awaitNothingPendingFor(sale.store());

        JsonNode byDay = reporting.report("receipts", sale.store(), day, day, "groupBy", "day");
        assertThat(byDay.get("rows")).hasSize(1);
        assertThat(amount(byDay.get("rows").get(0), "onAccount")).isEqualByComparingTo("7.50");
        assertThat(amount(byDay.get("rows").get(0), "received")).isEqualByComparingTo("25.00");
        JsonNode byMethod = reporting.report("receipts", sale.store(), day, day, "groupBy", "method");
        assertThat(byMethod.get("rows").valueStream().map(row -> row.get("key").asString()))
                .containsExactlyInAnyOrder("CASH", card);
        assertThat(amount(byMethod.get("totals"), "received")).isEqualByComparingTo("25.00");
        assertThat(amount(byMethod.get("totals"), "onAccount")).isEqualByComparingTo("7.50");
    }

    private static BigDecimal amount(JsonNode row, String column) {
        return row.get(column).get("amount").decimalValue();
    }
}
