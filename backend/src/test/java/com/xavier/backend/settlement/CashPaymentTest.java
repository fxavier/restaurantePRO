package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** A cash payment is a Payment and its cash receipt, created together in one transaction (D50 §7.5). */
@SettlementModuleTest
class CashPaymentTest {

    @Autowired
    SettlementFixtures fixtures;

    @Test
    void payingCashRecordsOnePaymentAndOneReceiptAndGivesChange() throws Exception {
        Sale sale = fixtures.sale("2"); // 25.00

        ResultActions paid = fixtures.payCash(sale, "25.00", "30.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.methodKind").value("CASH"))
                .andExpect(jsonPath("$.change.amount").value(5.00))
                .andExpect(jsonPath("$.sessionId").value(sale.session().toString()));
        UUID attempt = SettlementFixtures.id(paid);
        paid.andExpect(header().string("Location", SettlementFixtures.ATTEMPTS + "/" + attempt));

        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.receipts(sale.order())).isEqualTo(1);
        assertThat(fixtures.count("""
                SELECT count(*) FROM cash.cash_movement m JOIN settlement.payment p ON p.id = m.source_id
                WHERE p.attempt_id = ? AND m.method_code = 'CASH' AND m.amount = 25.00
                  AND m.session_id = ?""", attempt, sale.session())).isEqualTo(1);
    }

    @Test
    void theSettlementViewShowsWhatIsPaidAndWhatIsStillDue() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "10.00", "10.00").andExpect(status().isCreated());

        fixtures.settlement(sale.cashier(), sale.order()).andExpect(status().isOk())
                .andExpect(jsonPath("$.due.amount").value(25.00))
                .andExpect(jsonPath("$.paid.amount").value(10.00))
                .andExpect(jsonPath("$.balance.amount").value(15.00))
                .andExpect(jsonPath("$.financialState").value("PARTIALLY_SETTLED"))
                .andExpect(jsonPath("$.fiscalState").value("NONE"))
                .andExpect(jsonPath("$.lifecycle").value("OPEN"))
                .andExpect(jsonPath("$.openAttempts").isEmpty());
    }

    @Test
    void anAmountAboveTheBalanceIsRefused() throws Exception {
        Sale sale = fixtures.sale("2");

        fixtures.payCash(sale, "25.01", "30.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_BALANCE"));
        assertThat(fixtures.payments(sale.order())).isZero();
    }

    @Test
    void aCashierWithoutAnOpenSessionCannotCollect() throws Exception {
        Sale sale = fixtures.sale("2");
        var stranger = com.xavier.backend.cash.CashFixtures.pos(sale.store());

        fixtures.pay(stranger, sale.order(), "CASH", "25.00", "25.00", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_OPEN_CASH_SESSION"));
    }

    @Test
    void theFirstAttemptLocksTheOrderForPayment() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "10.00", "10.00").andExpect(status().isCreated());

        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = 'PAYMENT'",
                sale.order())).isEqualTo(1);
    }
}
