package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §8.4 "reserva transacional do limite", A20 end-to-end half (D73, D76): two terminals selling two orders on account
 * to one customer at once, whose limit covers only one, through the HTTP route. Both requests are held behind the
 * customer's {@code credit_account} lock (pg_blocking_pids, no sleep), then race: exactly one is POSTED and the other
 * is 422 CREDIT_LIMIT_EXCEEDED, leaving nothing reserved and its order not frozen. And a credit sale racing a cash
 * payment of the same order queue on {@code order_settlement} first: one takes the balance, the other is refused.
 */
@SettlementModuleTest
class CreditLimitRaceTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    CustomersApi credit;

    @Test
    void twoTerminalsCannotPutOneCustomerOverTheLimit() throws Exception {
        UUID customer = customers.creditCustomer("30.00");
        Sale first = fixtures.sale("2"); // 25.00
        Sale second = fixtures.sale("2");

        List<Integer> statuses = fixtures.raceBehindRowLock(
                "SELECT customer_id FROM customers.credit_account WHERE customer_id = ? FOR UPDATE", customer,
                () -> status(fixtures.creditCharge(first, customer, "25.00")),
                () -> status(fixtures.creditCharge(second, customer, "25.00")));

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("25.00");
        assertThat(credit.credit(customer).reserved().amount()).isEqualByComparingTo("0");
        Sale refused = statuses.get(0) == 422 ? first : second;
        assertThat(fixtures.documents(refused.order())).as("the refused sale left no draft").isZero();
        JsonNode view = SettlementFixtures.json(fixtures.settlement(refused.cashier(), refused.order()));
        assertThat(view.get("creditCharges")).isEmpty();
    }

    @Test
    void aCreditSaleAndACashPaymentOfOneOrderTakeTheBalanceOnce() throws Exception {
        UUID customer = customers.creditCustomer("100.00");
        Sale sale = fixtures.sale("2");
        SettlementFixtures.Sale same = sale;
        var other = fixtures.secondCashier(sale);

        List<Integer> statuses = fixtures.raceBehindOrderLock(sale.order(),
                () -> status(fixtures.creditCharge(same, customer, "25.00")),
                () -> status(fixtures.pay(other, same.order(), "CASH", "25.00", "25.00",
                        UUID.randomUUID().toString())));

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        long payments = fixtures.payments(sale.order());
        assertThat(payments + credit.debtOf(customer).amount().signum()).as("either paid or on account").isEqualTo(1);
    }

    private static int status(org.springframework.test.web.servlet.ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }
}
