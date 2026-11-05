package com.xavier.backend.settlement.reconciliation;

import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.BASE;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.STORE_ZONE;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.confirmedAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D78, D63: a provider whose adapter is a {@code StatementSource} is asked for its statement with no transaction open,
 * and the lines are imported as a CSV would be (source PROVIDER). Fetching the same statement again returns its batch.
 */
@SettlementModuleTest
@Import(FakeStatementProvider.Config.class)
class StatementFetchTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FakeStatementProvider provider;

    Sale sale;
    String method;
    UUID payment;
    UUID attempt;

    @BeforeEach
    void anIntegratedPaymentAtAProviderThatFetchesStatements() throws Exception {
        sale = fixtures.sale("2"); // 25.00
        method = SettlementFixtures.freshCode("WALLET");
        fixtures.method(method, "INTEGRATED", FakeStatementProvider.CODE);
        attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), method, "25.00", null,
                UUID.randomUUID().toString()).andExpect(status().isAccepted()));
        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("SUCCEEDED");
        payment = fixtures.paymentOfAttempt(attempt);
    }

    @Test
    void theStatementIsFetchedWithNoTransactionAndImported() throws Exception {
        provider.calledInTransaction().clear();
        provider.willReturn(List.of(line(confirmedAt(jdbc, payment), "25.00", "STMT-" + attempt)));

        fetch(method, UUID.randomUUID().toString(), Permission.RECONCILIATION_MANAGE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("PROVIDER"))
                .andExpect(jsonPath("$.lineCount").value(1))
                .andExpect(jsonPath("$.entries.MATCHED").value(1));

        assertThat(provider.calledInTransaction()).containsExactly(false);
    }

    @Test
    void fetchingTheSameStatementAgainReturnsItsBatch() throws Exception {
        provider.willReturn(List.of(line(confirmedAt(jdbc, payment), "25.00", "STMT-" + attempt)));
        String batch = SettlementFixtures.json(fetch(method, UUID.randomUUID().toString(),
                Permission.RECONCILIATION_MANAGE).andExpect(status().isCreated())).get("id").asString();

        fetch(method, UUID.randomUUID().toString(), Permission.RECONCILIATION_MANAGE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(batch));
        assertThat(fixtures.count("SELECT count(*) FROM settlement.reconciliation_batch WHERE store_id = ?",
                sale.store())).isEqualTo(1);
    }

    @Test
    void aProviderThatCannotFetchIsRefused() throws Exception {
        String other = SettlementFixtures.freshCode("MPESA");
        fixtures.method(other, "INTEGRATED", FakePaymentProvider.CODE);

        fetch(other, UUID.randomUUID().toString(), Permission.RECONCILIATION_MANAGE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_STATEMENT_SOURCE"));
    }

    @Test
    void invalidProviderLinesAreRefusedLikeACsv() throws Exception {
        provider.willReturn(List.of(line(confirmedAt(jdbc, payment), "0", null)));

        fetch(method, UUID.randomUUID().toString(), Permission.RECONCILIATION_MANAGE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STATEMENT_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    @Test
    void fetchingNeedsReconciliationManageAndCallsNoProviderOtherwise() throws Exception {
        provider.calledInTransaction().clear();

        fetch(method, UUID.randomUUID().toString(), Permission.RECONCILIATION_VIEW)
                .andExpect(status().isForbidden());
        assertThat(provider.calledInTransaction()).isEmpty();
    }

    private ResultActions fetch(String code, String key, Permission permission) throws Exception {
        LocalDate today = LocalDate.now(STORE_ZONE);
        return mvc.perform(post(BASE + "/batches/fetch").header("Idempotency-Key", key).with(csrf())
                .with(CashFixtures.account(sale.store(), permission)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\": \"%s\", \"methodCode\": \"%s\", \"from\": \"%s\", \"to\": \"%s\"}"
                        .formatted(sale.store(), code, today.minusDays(1), today.plusDays(1))));
    }

    static StatementLine line(Instant at, String amount, String reference) {
        return new StatementLine(at, new BigDecimal(amount), BigDecimal.ZERO, reference, null, null);
    }
}
