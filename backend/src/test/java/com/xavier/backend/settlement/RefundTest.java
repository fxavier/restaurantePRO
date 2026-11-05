package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.model.event.RefundRecorded;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.JsonNode;

/**
 * D77, P30, A23: a Refund gives money back from a confirmed payment, to its own method, in the refunder's open cash
 * session, with an approver holding {@code PAYMENT_REFUND_APPROVE} who is not the refunder. Cash and external-terminal
 * refunds are a REFUND OUT movement of the method; a debt payment's refund also reopens the debt. Paid amounts are
 * net of refunds. What no issued document covers may be refunded, an invoiced sale only for its overpayment
 * (otherwise 422 {@code CORRECTION_REQUIRES_FISCAL_ADAPTER}, P31).
 */
@SettlementModuleTest
@RecordApplicationEvents
class RefundTest {

    static final UUID MANAGER = UUID.randomUUID();

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    CustomersApi credit;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    SettlementFacts facts;

    @Autowired
    ApplicationEvents events;

    Sale sale;

    @BeforeEach
    void aManagerApprovesAndASale() throws Exception {
        Mockito.reset(approvers);
        when(approvers.verify(any(), any(), eq(Permission.PAYMENT_REFUND_APPROVE), any())).thenReturn(MANAGER);
        sale = fixtures.sale("2"); // 25.00
    }

    @Test
    void aCashRefundIsOneRefundOutInTheRefundersSessionWithTheApproverAudited() throws Exception {
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        UUID payment = fixtures.paymentOf(sale.order());
        Pos refunder = fixtures.secondCashier(sale);

        JsonNode refund = SettlementFixtures.json(fixtures.refund(refunder, payment, "10.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(payment.toString()))
                .andExpect(jsonPath("$.methodCode").value("CASH"))
                .andExpect(jsonPath("$.amount.amount").value(10.00))
                .andExpect(jsonPath("$.approverEmployeeId").value(MANAGER.toString()))
                .andExpect(jsonPath("$.operatorEmployeeId").value(refunder.employeeId().toString())));
        UUID id = UUID.fromString(refund.get("id").asString());

        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE source_id = ? AND type = 'REFUND' "
                + "AND direction = 'OUT' AND method_code = 'CASH' AND amount = 10 AND session_id <> ?", id,
                sale.session())).as("in the refunder's session, not the payment's").isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'PAYMENT_REFUNDED' "
                + "AND entity_id = ? AND actor_id = ? AND approver_id = ? AND reason = 'returned'", id.toString(),
                refunder.employeeId(), MANAGER)).isEqualTo(1);
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.paid.amount").value(15.00))
                .andExpect(jsonPath("$.balance.amount").value(10.00))
                .andExpect(jsonPath("$.financialState").value("PARTIALLY_SETTLED"))
                .andExpect(jsonPath("$.refunds[0].id").value(id.toString()));
        assertThat(events.stream(RefundRecorded.class).filter(e -> e.refundId().equals(id))).singleElement()
                .satisfies(e -> assertThat(e.storeId()).isEqualTo(sale.store()));
        assertThat(facts.refundFact(id)).hasValueSatisfying(fact -> {
            assertThat(fact.paymentId()).isEqualTo(payment);
            assertThat(fact.amount().amount()).isEqualByComparingTo("10.00");
            assertThat(fact.orderId()).isEqualTo(sale.order());
            assertThat(fact.refundedByEmployeeId()).isEqualTo(refunder.employeeId());
            assertThat(fact.approverEmployeeId()).isEqualTo(MANAGER);
            assertThat(fact.terminalId()).isEqualTo(refunder.terminalId());
            assertThat(fact.businessDate()).isNotNull();
        });
    }

    @Test
    void aReplayWithTheSameKeyRefundsOnce() throws Exception {
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        UUID payment = fixtures.paymentOf(sale.order());
        String key = UUID.randomUUID().toString();

        String first = fixtures.refund(sale.cashier(), payment, "5.00", null, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String again = fixtures.refund(sale.cashier(), payment, "5.00", null, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(SettlementFixtures.json(again).get("id")).isEqualTo(SettlementFixtures.json(first).get("id"));
        assertThat(fixtures.count("SELECT count(*) FROM settlement.refund WHERE payment_id = ?", payment)).isEqualTo(1);
    }

    @Test
    void theRefundableIsThePaymentLessItsRefunds() throws Exception {
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        UUID payment = fixtures.paymentOf(sale.order());
        fixtures.refund(sale.cashier(), payment, "20.00").andExpect(status().isCreated());

        fixtures.refund(sale.cashier(), payment, "5.01").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_REFUNDABLE"))
                .andExpect(jsonPath("$.refundable").value(5.00));
        fixtures.refund(sale.cashier(), payment, "5.00").andExpect(status().isCreated());
    }

    @Test
    void anExternalTerminalRefundNeedsItsReferenceAndIsAMovementOfItsMethod() throws Exception {
        String card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null);
        UUID attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), card, "25.00", null,
                UUID.randomUUID().toString()));
        fixtures.confirm(sale.cashier(), attempt, "AUTH-1").andExpect(status().isOk());
        UUID payment = fixtures.paymentOfAttempt(attempt);

        fixtures.refund(sale.cashier(), payment, "25.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("EXTERNAL_REFERENCE_REQUIRED"));
        UUID refund = SettlementFixtures.id(fixtures.refund(sale.cashier(), payment, "25.00", "TERM-REF-9",
                        UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.methodCode").value(card))
                .andExpect(jsonPath("$.externalReference").value("TERM-REF-9")));

        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE source_id = ? AND type = 'REFUND' "
                + "AND method_code = ?", refund, card)).isEqualTo(1);
    }

    @Test
    void refundingADebtPaymentReopensTheDebt() throws Exception {
        UUID customer = customers.creditCustomer("100.00");
        fixtures.creditCharge(sale, customer, "25.00").andExpect(status().isCreated());
        JsonNode attempt = SettlementFixtures.json(fixtures.payDebt(sale.cashier(), customer, "CASH", "10.00",
                "10.00", UUID.randomUUID().toString()).andExpect(status().isCreated()));
        UUID payment = fixtures.paymentOfAttempt(UUID.fromString(attempt.get("id").asString()));
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("15.00");

        UUID refund = SettlementFixtures.id(fixtures.refund(sale.cashier(), payment, "4.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetType").value("CREDIT_ACCOUNT"))
                .andExpect(jsonPath("$.customerId").value(customer.toString())));

        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("19.00");
        assertThat(fixtures.count("SELECT count(*) FROM customers.credit_ledger_entry WHERE source_type = 'REFUND' "
                + "AND source_id = ? AND kind = 'DEBIT' AND amount = 4", refund)).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE source_id = ? AND type = 'REFUND'",
                refund)).as("the money left the drawer").isEqualTo(1);
        assertThat(facts.refundFact(refund)).hasValueSatisfying(fact -> {
            assertThat(fact.customerId()).isEqualTo(customer);
            assertThat(fact.orderId()).isNull();
        });
    }

    @Test
    void anInvoicedSaleIsNotRefundedWithoutTheFiscalAdapter() throws Exception {
        fixtures.payAndClose(sale);
        UUID payment = fixtures.paymentOf(sale.order());

        fixtures.refund(sale.cashier(), payment, "1.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CORRECTION_REQUIRES_FISCAL_ADAPTER"));
        assertThat(fixtures.count("SELECT count(*) FROM settlement.refund WHERE payment_id = ?", payment)).isZero();
    }

    @Test
    void theOverpaymentOfAnInvoicedSaleIsRefundable() throws Exception {
        fixtures.payAndClose(sale);
        UUID invoiced = fixtures.paymentOf(sale.order());
        // a second payment of the closed order, as an R2 build could leave it: the order is paid 30.00 for 25.00
        UUID extra = fixtures.r2Payment(sale, "5.00");

        fixtures.refund(sale.cashier(), extra, "5.01").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_REFUNDABLE"));
        fixtures.refund(sale.cashier(), invoiced, "5.01")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CORRECTION_REQUIRES_FISCAL_ADAPTER"));
        fixtures.refund(sale.cashier(), extra, "5.00").andExpect(status().isCreated());
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.paid.amount").value(25.00));
    }

    @Test
    void aRefundBeforeTheDocumentLetsAVoidWithinHeadroomAndTheOrderStillCloses() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(MANAGER);
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                "1", UUID.randomUUID().toString()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VOID_EXCEEDS_UNPAID"));

        fixtures.refund(sale.cashier(), fixtures.paymentOf(sale.order()), "12.50").andExpect(status().isCreated());
        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                "1", UUID.randomUUID().toString()).andExpect(status().isCreated());

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.financialState").value("SETTLED"))
                .andExpect(jsonPath("$.paid.amount").value(12.50));
    }

    @Test
    void anItemsPartitionWithItsDocumentIsNotRefundedButAnAmountPartitionIs() throws Exception {
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        UUID items = SettlementFixtures.id(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1"));
        UUID amount = SettlementFixtures.id(fixtures.partitionAmount(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), "5.00"));
        UUID itemsAttempt = SettlementFixtures.id(fixtures.payPartition(sale.cashier(), sale.order(), items, "CASH",
                "12.50", "12.50", UUID.randomUUID().toString()));
        UUID amountAttempt = SettlementFixtures.id(fixtures.payPartition(sale.cashier(), sale.order(), amount, "CASH",
                "5.00", "5.00", UUID.randomUUID().toString()));
        fixtures.partitionDocument(sale.cashier(), items, UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        fixtures.refund(sale.cashier(), fixtures.paymentOfAttempt(itemsAttempt), "1.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CORRECTION_REQUIRES_FISCAL_ADAPTER"));
        fixtures.refund(sale.cashier(), fixtures.paymentOfAttempt(amountAttempt), "5.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetType").value("PARTITION"))
                .andExpect(jsonPath("$.partitionId").value(amount.toString()));
        fixtures.partitions(sale.cashier(), sale.order())
                .andExpect(jsonPath("$[?(@.id == '%s')].paid.amount".formatted(amount)).value(0.0));
    }

    @Test
    void theRefunderNeedsAnOpenSession() throws Exception {
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());

        fixtures.refund(CashFixtures.pos(sale.store()), fixtures.paymentOf(sale.order()), "1.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_OPEN_CASH_SESSION"));
    }

    @Test
    void anApproverIsRequiredAndIsNotTheRefunder() throws Exception {
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        UUID payment = fixtures.paymentOf(sale.order());

        when(approvers.verify(any(), any(), eq(Permission.PAYMENT_REFUND_APPROVE), any()))
                .thenReturn(sale.cashier().employeeId());
        fixtures.refund(sale.cashier(), payment, "1.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL"));

        when(approvers.verify(any(), any(), eq(Permission.PAYMENT_REFUND_APPROVE), any()))
                .thenThrow(new ApprovalDeniedException());
        fixtures.refund(sale.cashier(), payment, "1.00").andExpect(status().isForbidden());
        assertThat(fixtures.count("SELECT count(*) FROM settlement.refund WHERE payment_id = ?", payment)).isZero();
    }

    @Test
    void aPaymentOfAnotherStoreIsForbiddenAndAnUnknownOneNotFound() throws Exception {
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        Sale elsewhere = fixtures.sale("1");

        fixtures.refund(elsewhere.cashier(), fixtures.paymentOf(sale.order()), "1.00")
                .andExpect(status().isForbidden());
        fixtures.refund(sale.cashier(), UUID.randomUUID(), "1.00").andExpect(status().isNotFound());
    }
}
