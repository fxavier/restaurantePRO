package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.model.event.PaymentRecorded;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** D84: a recorded payment publishes PaymentRecorded, and reporting pulls its immutable facts. */
@SettlementModuleTest
@RecordApplicationEvents
class SettlementFactsTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    SettlementFacts facts;

    @Autowired
    ApplicationEvents events;

    @Test
    void aPaymentToAPartitionIsAFactWithItsTargetCashDayAndTerminal() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.equalParts(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), 2)
                .andExpect(status().isCreated());
        UUID part = UUID.fromString(SettlementFixtures.json(fixtures.partitions(sale.cashier(), sale.order())).get(0)
                .get("id").asString());
        fixtures.payPartition(sale.cashier(), sale.order(), part, "CASH", "12.50", "12.50",
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        UUID payment = fixtures.paymentOf(sale.order());
        LocalDate day = LocalDate.parse(fixtures.currentDay(sale.cashier()).get("businessDate").asString());

        PaymentFact fact = facts.paymentFact(payment).orElseThrow();

        assertThat(fact.storeId()).isEqualTo(sale.store());
        assertThat(fact.businessDate()).isEqualTo(day);
        assertThat(fact.targetType()).isEqualTo("PARTITION");
        assertThat(fact.orderId()).isEqualTo(sale.order());
        assertThat(fact.partitionId()).isEqualTo(part);
        assertThat(fact.amount().amount()).isEqualByComparingTo("12.50");
        assertThat(fact.methodCode()).isEqualTo("CASH");
        assertThat(fact.collectedByEmployeeId()).isEqualTo(sale.cashier().employeeId());
        assertThat(fact.terminalId()).isEqualTo(sale.cashier().terminalId());
        assertThat(facts.paymentIds(sale.store(), day, day, PageRequest.of(0, 10)).getContent())
                .containsExactly(payment);
        assertThat(events.stream(PaymentRecorded.class))
                .contains(new PaymentRecorded(payment, sale.store()));
    }

    @Test
    void anR2PaymentWithoutABusinessDateTakesItsSessionsCashDay() throws Exception {
        Sale sale = fixtures.sale("2");
        LocalDate day = LocalDate.parse(fixtures.currentDay(sale.cashier()).get("businessDate").asString());
        UUID payment = fixtures.r2Payment(sale, "25.00"); // written as R2 did: no business_date, no terminal_id

        assertThat(facts.paymentFact(payment).orElseThrow().businessDate()).isEqualTo(day);
        assertThat(facts.paymentIds(sale.store(), day, day, PageRequest.of(0, 10)).getContent()).contains(payment);
        assertThat(facts.paymentIds(sale.store(), day.plusDays(1), day.plusDays(2), PageRequest.of(0, 10))
                .getContent()).doesNotContain(payment);
    }

    @Test
    void refundsAndCreditChargesAreNoFactsYet() {
        assertThat(facts.refundFact(UUID.randomUUID())).isEmpty();
        assertThat(facts.creditChargeFact(UUID.randomUUID())).isEmpty();
        assertThat(facts.refundIds(UUID.randomUUID(), LocalDate.now(), LocalDate.now(), PageRequest.of(0, 5)))
                .isEmpty();
        assertThat(facts.creditChargeIds(UUID.randomUUID(), LocalDate.now(), LocalDate.now(), PageRequest.of(0, 5)))
                .isEmpty();
    }
}
