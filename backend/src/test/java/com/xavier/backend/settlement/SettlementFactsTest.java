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

    @Autowired
    com.xavier.backend.customers.CustomersFixtures customers;

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
    void refundsAreNoFactsYet() {
        assertThat(facts.refundFact(UUID.randomUUID())).isEmpty();
        assertThat(facts.refundIds(UUID.randomUUID(), LocalDate.now(), LocalDate.now(), PageRequest.of(0, 5)))
                .isEmpty();
    }

    @Test
    void aPostedCreditChargeIsAFactOfItsOrdersBusinessDayAndNoPayment() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID customer = customers.creditCustomer("100.00");
        com.xavier.backend.settlement.SettlementFixtures.json(fixtures.creditCharge(sale, customer, "25.00")
                .andExpect(status().isCreated()));
        UUID charge = UUID.fromString(SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("creditCharges").get(0).get("id").asString());
        String document = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("documentId").asString();
        LocalDate day = LocalDate.parse(fixtures.currentDay(sale.cashier()).get("businessDate").asString());

        CreditChargeFact fact = facts.creditChargeFact(charge).orElseThrow();

        assertThat(fact.storeId()).isEqualTo(sale.store());
        assertThat(fact.businessDate()).isEqualTo(day);
        assertThat(fact.amount().amount()).isEqualByComparingTo("25.00");
        assertThat(fact.orderId()).isEqualTo(sale.order());
        assertThat(fact.partitionId()).isNull();
        assertThat(fact.customerId()).isEqualTo(customer);
        assertThat(fact.documentId()).isEqualTo(UUID.fromString(document));
        assertThat(fact.postedByEmployeeId()).isEqualTo(sale.cashier().employeeId());
        assertThat(fact.terminalId()).isEqualTo(sale.cashier().terminalId());
        assertThat(fact.at()).isNotNull();
        assertThat(facts.creditChargeIds(sale.store(), day, day, PageRequest.of(0, 10)).getContent())
                .containsExactly(charge);
        assertThat(facts.creditChargeIds(sale.store(), day.plusDays(1), day.plusDays(1), PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(facts.paymentIds(sale.store(), day, day, PageRequest.of(0, 10))).as("credit is not a payment")
                .isEmpty();
        assertThat(events.stream(com.xavier.backend.settlement.model.event.CreditChargePosted.class))
                .contains(new com.xavier.backend.settlement.model.event.CreditChargePosted(charge, sale.store()));
        assertThat(facts.creditChargeFact(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aDebtPaymentIsAPaymentFactOfItsCustomer() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID customer = customers.creditCustomer("100.00");
        fixtures.creditCharge(sale, customer, "25.00").andExpect(status().isCreated());
        UUID attempt = SettlementFixtures.id(fixtures.payDebt(sale.cashier(), customer, "CASH", "5.00", "5.00",
                UUID.randomUUID().toString()));

        PaymentFact fact = facts.paymentFact(fixtures.paymentOfAttempt(attempt)).orElseThrow();

        assertThat(fact.targetType()).isEqualTo("CREDIT_ACCOUNT");
        assertThat(fact.customerId()).isEqualTo(customer);
        assertThat(fact.orderId()).isNull();
        assertThat(fact.amount().amount()).isEqualByComparingTo("5.00");
    }
}
