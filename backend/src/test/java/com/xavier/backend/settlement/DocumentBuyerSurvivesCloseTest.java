package com.xavier.backend.settlement;

import static com.xavier.backend.cash.CashFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The buyer given on {@code /documents} is printed on the invoice even when that issue failed and {@code /close}
 * (which carries no buyer) issues the pending draft later: an R2 draft is never rewritten by a later step.
 */
@SettlementModuleTest
class DocumentBuyerSurvivesCloseTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    InternalFiscalAdapter fiscalAdapter;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void realAdapter() {
        Mockito.reset(fiscalAdapter);
    }

    @Test
    void theBuyerOfAFailedDocumentsCallIsOnTheDocumentTheCloseIssues() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        long version = fixtures.orderVersion(sale.cashier(), sale.order());
        Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter).issue(any());
        mvc.perform(idem(post(SettlementFixtures.ORDERS + "/" + sale.order() + "/documents"), UUID.randomUUID()
                        .toString()).with(sale.cashier().with(SettlementFixtures.CASHIER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyer\": {\"name\": \"Cliente Lda\", \"taxId\": \"400111222\"}}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("FISCAL_PENDING"));
        Mockito.reset(fiscalAdapter);

        fixtures.close(sale.cashier(), sale.order(), version, UUID.randomUUID().toString())
                .andExpect(status().isOk()).andExpect(jsonPath("$.fiscalState").value("ISSUED"));

        Map<String, Object> document = jdbc.sql("""
                        SELECT status, buyer_name, buyer_tax_id FROM fiscal.fiscal_document WHERE source_id = :order""")
                .param("order", sale.order()).query().singleRow();
        assertThat(document).containsEntry("status", "ISSUED").containsEntry("buyer_name", "Cliente Lda")
                .containsEntry("buyer_tax_id", "400111222");
    }
}
