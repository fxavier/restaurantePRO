package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.service.OrderSettlementSteps;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D63: {@code /close} is a multi-step command (s1 freeze + draft, s2 issue with no transaction, s3 close). A failure
 * after any step has committed, then a retry with the same Idempotency-Key, ends in one document number, one close and
 * the final-state response. A fiscal failure is 422 FISCAL_PENDING (not stored), and the retry issues with no gap.
 */
@SettlementModuleTest
class OrderCloseResumeTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    OrderSettlementSteps steps;

    @Autowired
    InternalFiscalAdapter fiscalAdapter;

    Sale sale;
    long version;
    String key;

    @BeforeEach
    void aPaidSale() throws Exception {
        sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        version = fixtures.orderVersion(sale.cashier(), sale.order());
        key = UUID.randomUUID().toString();
    }

    @AfterEach
    void realSteps() {
        Mockito.reset(steps, fiscalAdapter);
    }

    @Test
    void aFailureAfterTheDraftStepIsResumedByTheRetry() throws Exception {
        Mockito.doAnswer(thenFail("s1")).when(steps).prepareDocument(any(), any(), any(), any());

        fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isInternalServerError());
        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE source_id = ? AND status = 'DRAFT'",
                sale.order())).as("s1 committed").isEqualTo(1);
        Mockito.reset(steps);

        retryEndsClosedOnce();
    }

    @Test
    void aFailureAfterTheIssueStepIsResumedByTheRetry() throws Exception {
        Mockito.doAnswer(thenFail("s2")).when(steps).issue(any(), any());

        fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isInternalServerError());
        assertThat(fixtures.issuedNumber(sale.order())).as("s2 committed").isEqualTo(1);
        Mockito.reset(steps);

        retryEndsClosedOnce();
    }

    @Test
    void aFailureAfterTheCloseStepIsResumedByTheRetry() throws Exception {
        Mockito.doAnswer(thenFail("s3")).when(steps).close(any(), any());

        fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isInternalServerError());
        assertThat(fixtures.closes(sale.order())).as("s3 committed").isEqualTo(1);
        Mockito.reset(steps);

        retryEndsClosedOnce();
    }

    @Test
    void aFiscalFailureIsAPendencyAndTheRetryIssuesWithNoGap() throws Exception {
        Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter).issue(any());

        fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FISCAL_PENDING"))
                .andExpect(jsonPath("$.documentId").isNotEmpty());
        assertThat(fixtures.nextNumber(sale.series())).as("no number used").isEqualTo(1);
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.fiscalState").value("DRAFT"))
                .andExpect(jsonPath("$.financialState").value("SETTLED"))
                .andExpect(jsonPath("$.lifecycle").value("OPEN"));
        Mockito.reset(fiscalAdapter);

        retryEndsClosedOnce();
        assertThat(fixtures.payments(sale.order())).as("nobody is charged again").isEqualTo(1);
    }

    @Test
    void aFiscalFailureOnTheDocumentsCommandIsRetriedWithTheSameKey() throws Exception {
        Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter).issue(any());
        fixtures.issue(sale.cashier(), sale.order(), key).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FISCAL_PENDING"));
        Mockito.reset(fiscalAdapter);

        fixtures.issue(sale.cashier(), sale.order(), key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED")).andExpect(jsonPath("$.number").value(1));
        assertThat(fixtures.nextNumber(sale.series())).isEqualTo(2);
    }

    private void retryEndsClosedOnce() throws Exception {
        fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CLOSED"))
                .andExpect(jsonPath("$.financialState").value("SETTLED"))
                .andExpect(jsonPath("$.fiscalState").value("ISSUED"));
        String stored = fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(SettlementFixtures.json(stored).get("lifecycle").asString()).isEqualTo("CLOSED");
        assertThat(fixtures.documents(sale.order())).isEqualTo(1);
        assertThat(fixtures.issuedNumber(sale.order())).isEqualTo(1);
        assertThat(fixtures.nextNumber(sale.series())).isEqualTo(2);
        assertThat(fixtures.closes(sale.order())).isEqualTo(1);
        assertThat(fixtures.tableFree(sale.order())).isTrue();
    }

    private static Answer<Object> thenFail(String step) {
        return call -> {
            call.callRealMethod();
            throw new IllegalStateException("injected failure after " + step);
        };
    }
}
