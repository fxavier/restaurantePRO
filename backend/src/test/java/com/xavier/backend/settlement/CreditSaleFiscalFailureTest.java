package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D74: when the document of a credit sale cannot be issued (s2), the sale answers 422 FISCAL_PENDING, the charge stays
 * RESERVED with the customer's limit held and shows in the pendings, the order cannot close, and the retry with the
 * same key issues and posts once. Before the document is issued the charge can be cancelled, which gives the limit
 * back; after, it cannot.
 */
@SettlementModuleTest
class CreditSaleFiscalFailureTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    CustomersApi credit;

    @Autowired
    InternalFiscalAdapter fiscalAdapter;

    Sale sale;
    UUID customer;
    String key;

    @BeforeEach
    void aCreditSaleWhoseDocumentFails() throws Exception {
        sale = fixtures.sale("2"); // 25.00
        customer = customers.creditCustomer("100.00");
        key = UUID.randomUUID().toString();
        Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter).issue(any());
        fixtures.creditCharge(sale.cashier(), sale.order(), customer, "25.00", null, key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FISCAL_PENDING"))
                .andExpect(jsonPath("$.documentId").isNotEmpty());
    }

    @AfterEach
    void realAdapter() {
        Mockito.reset(fiscalAdapter);
    }

    @Test
    void theChargeStaysReservedVisibleAndBlocksTheClose() throws Exception {
        assertThat(credit.credit(customer).reserved().amount()).isEqualByComparingTo("25.00");
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo(BigDecimal.ZERO);
        mvc.perform(get("/api/v1/backoffice/settlement/pending").param("storeId", sale.store().toString())
                        .with(CashFixtures.account(sale.store(), Permission.PAYMENT_RESOLVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedCreditCharges[0].orderId").value(sale.order().toString()))
                .andExpect(jsonPath("$.reservedCreditCharges[0].status").value("RESERVED"))
                .andExpect(jsonPath("$.fiscalDrafts[0].orderId").value(sale.order().toString()));
        Mockito.reset(fiscalAdapter);

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOT_SETTLED"))
                .andExpect(jsonPath("$.chargeId").isNotEmpty());
    }

    @Test
    void theRetryWithTheSameKeyIssuesAndPostsOnce() throws Exception {
        Mockito.reset(fiscalAdapter);

        fixtures.creditCharge(sale.cashier(), sale.order(), customer, "25.00", null, key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"));

        assertThat(fixtures.issuedNumber(sale.order())).isEqualTo(1);
        assertThat(fixtures.nextNumber(sale.series())).isEqualTo(2);
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("25.00");
        assertThat(credit.credit(customer).reserved().amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void cancellingBeforeTheIssueGivesTheLimitBack() throws Exception {
        UUID charge = chargeId();

        fixtures.cancelCharge(sale.cashier(), charge, "customer pays cash").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releaseReason").value("customer pays cash"));

        assertThat(credit.credit(customer).reserved().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.financialState").value("UNSETTLED"))
                .andExpect(jsonPath("$.credit.amount").value(0));
        Mockito.reset(fiscalAdapter);
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aPostedChargeIsNotCancelled() throws Exception {
        Mockito.reset(fiscalAdapter);
        fixtures.creditCharge(sale.cashier(), sale.order(), customer, "25.00", null, key)
                .andExpect(status().isCreated());

        fixtures.cancelCharge(sale.cashier(), chargeId(), "too late").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CHARGE_POSTED"));
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("25.00");
    }

    @Test
    void aReleasedChargeIsNotCancelledTwice() throws Exception {
        UUID charge = chargeId();
        fixtures.cancelCharge(sale.cashier(), charge, "once").andExpect(status().isOk());

        fixtures.cancelCharge(sale.cashier(), charge, "twice").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_CHARGE_STATE"));
    }

    private UUID chargeId() throws Exception {
        return UUID.fromString(SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("creditCharges").get(0).get("id").asString());
    }
}
