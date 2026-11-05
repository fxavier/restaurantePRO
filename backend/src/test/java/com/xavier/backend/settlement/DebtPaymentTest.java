package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.model.event.ReceiptSlipRequested;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.JsonNode;

/**
 * A21, D75: a debt payment is a payment attempt on the customer's CREDIT_ACCOUNT, by the R2 flow of its method. Once
 * it succeeds it is real money received (a Payment and its cash receipt in the session), its CREDIT settles the
 * customer's debits (oldest first, or the ones named), and a non-fiscal slip is requested once. At most the open
 * debt (P33: no advance).
 */
@SettlementModuleTest
@RecordApplicationEvents
class DebtPaymentTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    CustomersApi credit;

    @Autowired
    ApplicationEvents events;

    Sale sale;
    UUID customer;
    UUID olderDebit;
    UUID newerDebit;

    @BeforeEach
    void aCustomerOwingTwoCreditSales() throws Exception {
        customer = customers.creditCustomer("100.00");
        sale = fixtures.sale("2"); // 25.00
        olderDebit = debitOf(sale, "25.00");
        newerDebit = debitOf(fixtures.sale("1"), "12.50");
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("37.50");
    }

    @Test
    void aCashDebtPaymentIsMoneyReceivedAndSettlesTheOldestDebitFirst() throws Exception {
        long movements = fixtures.movementsOfStore(sale.store());

        JsonNode attempt = SettlementFixtures.json(fixtures.payDebt(sale.cashier(), customer, "CASH", "30.00",
                        "50.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.targetType").value("CREDIT_ACCOUNT"))
                .andExpect(jsonPath("$.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.orderId").isEmpty())
                .andExpect(jsonPath("$.change.amount").value(20.00)));

        UUID payment = paymentOf(attempt);
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE source_id = ? AND session_id = ?",
                payment, sale.session())).as("a debt payment is money received").isEqualTo(1);
        assertThat(fixtures.movementsOfStore(sale.store())).isEqualTo(movements + 1);
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("7.50");
        assertThat(settled(olderDebit)).isEqualByComparingTo("25.00");
        assertThat(settled(newerDebit)).isEqualByComparingTo("5.00");
        assertThat(fixtures.count("SELECT count(*) FROM settlement.payment_allocation WHERE payment_id = ? AND "
                + "target_type = 'CREDIT_ACCOUNT' AND target_id = ?", payment, customer)).isEqualTo(1);
        assertThat(events.stream(ReceiptSlipRequested.class).filter(e -> e.paymentId().equals(payment)))
                .singleElement().satisfies(slip -> {
                    assertThat(slip.terminalId()).isEqualTo(sale.cashier().terminalId());
                    assertThat(slip.storeId()).isEqualTo(sale.store());
                    assertThat(slip.lines()).anyMatch(line -> line.contains("30.00"))
                            .anyMatch(line -> line.contains("7.50"));
                });
    }

    @Test
    void anInactiveCustomersSlipStillNamesThem() throws Exception {
        fixtures.count("WITH off AS (UPDATE customers.customer SET active = false WHERE id = ? RETURNING 1) "
                + "SELECT count(*) FROM off", customer); // deactivated after the sales: the debt is still paid

        UUID payment = paymentOf(SettlementFixtures.json(fixtures.payDebt(sale.cashier(), customer, "CASH", "10.00",
                "10.00", UUID.randomUUID().toString()).andExpect(status().isCreated())));

        assertThat(events.stream(ReceiptSlipRequested.class).filter(e -> e.paymentId().equals(payment)))
                .singleElement().satisfies(slip -> assertThat(slip.lines())
                        .anyMatch(line -> line.startsWith("Cliente: Credit ")));
    }

    @Test
    void namedDebitsAreSettledInTheOrderGiven() throws Exception {
        fixtures.payDebt(sale.cashier(), customer, "CASH", "15.00", "15.00", UUID.randomUUID().toString(),
                newerDebit, olderDebit).andExpect(status().isCreated());

        assertThat(settled(newerDebit)).isEqualByComparingTo("12.50");
        assertThat(settled(olderDebit)).isEqualByComparingTo("2.50");
    }

    @Test
    void aReplayWithTheSameKeyPaysAndPrintsOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = fixtures.payDebt(sale.cashier(), customer, "CASH", "10.00", "10.00", key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String again = fixtures.payDebt(sale.cashier(), customer, "CASH", "10.00", "10.00", key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(SettlementFixtures.json(again).get("id")).isEqualTo(SettlementFixtures.json(first).get("id"));
        UUID payment = paymentOf(SettlementFixtures.json(first));
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("27.50");
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE source_id = ?", payment))
                .isEqualTo(1);
        assertThat(events.stream(ReceiptSlipRequested.class).filter(e -> e.paymentId().equals(payment))).hasSize(1);
    }

    @Test
    void moreThanTheDebtIsRefused() throws Exception {
        fixtures.payDebt(sale.cashier(), customer, "CASH", "37.51", "40.00", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_DEBT"))
                .andExpect(jsonPath("$.debt").value(37.50));
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("37.50");
    }

    @Test
    void anExternalTerminalPaymentSettlesTheNamedDebitWhenConfirmed() throws Exception {
        String method = SettlementFixtures.freshCode("EXT");
        fixtures.method(method, "EXTERNAL_TERMINAL", null);
        UUID attempt = SettlementFixtures.id(fixtures.payDebt(sale.cashier(), customer, method, "12.50", null,
                        UUID.randomUUID().toString(), newerDebit)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")));
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("37.50");

        fixtures.payDebt(sale.cashier(), customer, "CASH", "1.00", "1.00", UUID.randomUUID().toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_IN_DOUBT"));
        fixtures.confirm(sale.cashier(), attempt, "AUTH-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(settled(newerDebit)).isEqualByComparingTo("12.50");
        assertThat(settled(olderDebit)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("25.00");
    }

    @Test
    void namedDebitsAreCheckedBeforeAnyMoneyIsTaken() throws Exception {
        String method = SettlementFixtures.freshCode("EXT");
        fixtures.method(method, "EXTERNAL_TERMINAL", null);
        UUID other = customers.creditCustomer("100.00");
        UUID foreignDebit = UUID.fromString(SettlementFixtures.json(fixtures.creditCharge(fixtures.sale("1"), other,
                "12.50").andExpect(status().isCreated())).get("ledgerEntryId").asString());
        fixtures.payDebt(sale.cashier(), customer, "CASH", "12.50", "12.50", UUID.randomUUID().toString(),
                newerDebit).andExpect(status().isCreated()); // the newer debit is now settled

        for (UUID named : new UUID[] {UUID.randomUUID(), foreignDebit, newerDebit}) {
            fixtures.payDebt(sale.cashier(), customer, method, "5.00", null, UUID.randomUUID().toString(), named)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("DEBIT_NOT_OPEN"))
                    .andExpect(jsonPath("$.debitEntryId").value(named.toString()));
        }
        fixtures.payDebt(sale.cashier(), customer, method, "25.01", null, UUID.randomUUID().toString(), olderDebit)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_DEBT"))
                .andExpect(jsonPath("$.debt").value(25.00));
        assertThat(fixtures.count("SELECT count(*) FROM settlement.payment_attempt WHERE credit_account_id = ? "
                + "AND method_code = ?", customer, method)).as("no attempt, no money taken").isZero();
    }

    @Test
    void anIntegratedDebtPaymentIsPostedWhenTheProviderCharges() throws Exception {
        String method = SettlementFixtures.freshCode("PAY");
        fixtures.method(method, "INTEGRATED", FakePaymentProvider.CODE);

        UUID attempt = SettlementFixtures.id(fixtures.payDebt(sale.cashier(), customer, method, "20.00", null,
                UUID.randomUUID().toString()).andExpect(status().isAccepted()));

        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("17.50");
        assertThat(settled(olderDebit)).isEqualByComparingTo("20.00");
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE source_id = ?",
                fixtures.paymentOfAttempt(attempt))).isEqualTo(1);
    }

    @Test
    void aCancelledDebtPaymentLeavesTheDebt() throws Exception {
        String method = SettlementFixtures.freshCode("EXT");
        fixtures.method(method, "EXTERNAL_TERMINAL", null);
        UUID attempt = SettlementFixtures.id(fixtures.payDebt(sale.cashier(), customer, method, "12.50", null,
                UUID.randomUUID().toString()));

        fixtures.cancelAttempt(sale.cashier(), attempt, "card declined").andExpect(status().isOk());

        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("37.50");
        fixtures.payDebt(sale.cashier(), customer, "CASH", "1.00", "1.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated());
    }

    @Test
    void aCustomerWithoutCreditAccountHasNoDebtToPay() throws Exception {
        UUID walkIn = customers.newCustomer(CustomersFixtures.pos(customers.newStore()), "Walk in", null, null);

        fixtures.payDebt(sale.cashier(), walkIn, "CASH", "1.00", "1.00", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CREDIT_ACCOUNT_MISSING"));
    }

    /** A credit sale of the whole order for the customer; @return its debit entry on the ledger. */
    private UUID debitOf(Sale credited, String amount) throws Exception {
        return UUID.fromString(SettlementFixtures.json(fixtures.creditCharge(credited, customer, amount)
                .andExpect(status().isCreated())).get("ledgerEntryId").asString());
    }

    private BigDecimal settled(UUID debit) {
        return fixtures.decimal("SELECT coalesce(sum(amount), 0) FROM customers.credit_settlement "
                + "WHERE debit_entry_id = ? AND reversal_entry_id IS NULL", debit);
    }

    private UUID paymentOf(JsonNode attempt) {
        return fixtures.paymentOfAttempt(UUID.fromString(attempt.get("id").asString()));
    }
}
