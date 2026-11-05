package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementTestClock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Reports filter by business date, never by a UTC range (D85, P37): a table opened before midnight and paid after it
 * — on the night the clocks of Lisbon spring forward (2027-03-28, 01:00 → 02:00) — belongs, sale and money alike,
 * to the business day it opened in, while {@code local_hour} is the store's wall clock at the instant (02:00 WEST,
 * the hour after the gap).
 */
@ReportingModuleTest
class ReportByBusinessDayTest {

    static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
    static final LocalDate OPENED_ON = LocalDate.of(2027, 3, 27);
    static final LocalDate AFTER_MIDNIGHT = LocalDate.of(2027, 3, 28);

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    SettlementTestClock clock;

    Sale sale;
    UUID document;
    UUID payment;

    @BeforeEach
    void aTableOpenedBeforeMidnightAndPaidAfterTheDstJump() throws Exception {
        clock.moveTo(ZonedDateTime.of(OPENED_ON, LocalTime.of(23, 30), LISBON).toInstant());
        sale = reporting.saleIn(LISBON, LocalTime.of(5, 0), "2");
        clock.moveTo(ZonedDateTime.of(AFTER_MIDNIGHT, LocalTime.of(2, 30), LISBON).toInstant()); // 01:30Z
        sales.payAndClose(sale);
        document = reporting.issuedDocument(sale.order());
        payment = reporting.payments(sale.order()).getFirst();
        reporting.awaitSalesRecord(document);
        reporting.awaitPaymentRecord(payment);
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    @Test
    void theSaleKeepsTheBusinessDayItOpenedInWithTheLocalHourOfItsIssue() throws Exception {
        Map<String, Object> record = reporting.row("""
                SELECT business_date, local_date, local_hour FROM reporting.sales_record WHERE document_id = ?""",
                document);
        assertThat(record).containsEntry("business_date", java.sql.Date.valueOf(OPENED_ON))
                .containsEntry("local_date", java.sql.Date.valueOf(AFTER_MIDNIGHT))
                .containsEntry("local_hour", 2);

        JsonNode byHour = reporting.report("sales", sale.store(), OPENED_ON, OPENED_ON, "groupBy", "hour");
        assertThat(byHour.get("rows")).hasSize(1);
        assertThat(byHour.get("rows").get(0).get("key").asString()).isEqualTo("02");
        assertThat(byHour.get("rows").get(0).get("gross").get("amount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(reporting.report("sales", sale.store(), AFTER_MIDNIGHT, AFTER_MIDNIGHT, "groupBy", "day")
                .get("rows")).isEmpty();
    }

    @Test
    void theMoneyBelongsToTheCashDayOfItsSession() throws Exception {
        assertThat(reporting.row("""
                SELECT business_date, local_hour FROM reporting.payment_record
                WHERE source_type = 'PAYMENT' AND source_id = ?""", payment))
                .containsEntry("business_date", java.sql.Date.valueOf(OPENED_ON))
                .containsEntry("local_hour", 2);

        JsonNode byDay = reporting.report("receipts", sale.store(), OPENED_ON, OPENED_ON, "groupBy", "day");
        assertThat(byDay.get("rows")).hasSize(1);
        assertThat(byDay.get("rows").get(0).get("key").asString()).isEqualTo(OPENED_ON.toString());
        assertThat(byDay.get("rows").get(0).get("received").get("amount").decimalValue())
                .isEqualByComparingTo("25.00");
        assertThat(reporting.report("receipts", sale.store(), AFTER_MIDNIGHT, AFTER_MIDNIGHT, "groupBy", "day")
                .get("rows")).isEmpty();
    }
}
