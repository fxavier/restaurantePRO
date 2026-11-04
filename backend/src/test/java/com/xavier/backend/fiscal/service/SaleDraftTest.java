package com.xavier.backend.fiscal.service;

import static com.xavier.backend.fiscal.FiscalActors.admin;
import static com.xavier.backend.fiscal.FiscalFixtures.saleDraft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalModuleTest;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.shared.exception.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** FiscalApi.createSaleDraft (D36 MANDATORY, D50 §7.6 tx-A), saleDocumentFor and countDrafts. */
@FiscalModuleTest
class SaleDraftTest {

    @Autowired
    FiscalApi fiscal;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aDraftNeedsTheCallersTransaction() {
        assertThatThrownBy(() -> fiscal.createSaleDraft(saleDraft(UUID.randomUUID(), UUID.randomUUID(), null)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aDraftIsIdempotentPerOrderAndIsAPendingDocumentOfItsStore() throws Exception {
        UUID store = fixtures.newStore();
        UUID order = UUID.randomUUID();

        DocumentView first = tx.execute(s -> fiscal.createSaleDraft(saleDraft(store, order, null)));
        DocumentView again = tx.execute(s -> fiscal.createSaleDraft(saleDraft(store, order, null)));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(first.status()).isEqualTo("DRAFT");
        assertThat(first.sourceType()).isEqualTo("ORDER");
        assertThat(first.sourceId()).isEqualTo(order);
        assertThat(first.totalGross()).isEqualTo(FiscalFixtures.mzn("116.00"));
        assertThat(fiscal.saleDocumentFor(order)).map(DocumentView::id).contains(first.id());
        assertThat(fiscal.saleDocumentFor(UUID.randomUUID())).isEmpty();
        assertThat(fiscal.countDrafts(store)).isEqualTo(1);
        assertThat(documentsOf(order)).isEqualTo(1);
    }

    @Test
    void theDocumentKeepsTheCompanyStoreAndBuyerAsTheyWereWhenItWasDrafted() throws Exception {
        UUID store = fixtures.newStore();
        UUID order = UUID.randomUUID();
        Buyer buyer = new Buyer("Cliente Lda", "500999888", "Av. 24 de Julho");
        tx.execute(s -> fiscal.createSaleDraft(saleDraft(store, order, buyer)));

        renameStore(store, "Loja Renomeada");

        DocumentView document = fiscal.saleDocumentFor(order).orElseThrow();
        assertThat(document.storeName()).isEqualTo("Loja Fiscal");
        assertThat(document.companyName()).isEqualTo("Test Company");
        assertThat(document.companyTaxId()).isEqualTo("TEST-TAX-ID");
        assertThat(document.companyCountry()).isEqualTo("MZ");
        assertThat(document.buyer()).isEqualTo(buyer);
        assertThat(document.lines()).singleElement().satisfies(line -> {
            assertThat(line.description()).isEqualTo("Cerveja 2M");
            assertThat(line.unitPrice()).isEqualTo(FiscalFixtures.mzn("58.00"));
        });
    }

    @Test
    void anUnknownStoreIsNotFound() {
        assertThatThrownBy(() -> tx.execute(s -> fiscal.createSaleDraft(
                saleDraft(UUID.randomUUID(), UUID.randomUUID(), null))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void twoConcurrentDraftsForOneOrderLeaveOneDocument() throws Exception {
        UUID store = fixtures.newStore();
        UUID order = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> draft = () -> {
            start.await();
            try {
                return tx.execute(s -> fiscal.createSaleDraft(saleDraft(store, order, null))).id().toString();
            } catch (RuntimeException e) {
                return e.getClass().getSimpleName();
            }
        };

        List<String> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = List.of(pool.submit(draft), pool.submit(draft));
            start.countDown();
            for (Future<String> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
        }

        String id = fiscal.saleDocumentFor(order).orElseThrow().id().toString();
        assertThat(outcomes).contains(id).allMatch(o -> o.equals(id) || o.equals("SaleDraftConflictException"));
        assertThat(documentsOf(order)).isEqualTo(1);
    }

    private long documentsOf(UUID order) {
        return jdbc.sql("SELECT count(*) FROM fiscal.fiscal_document WHERE source_id = :order")
                .param("order", order).query(Long.class).single();
    }

    private void renameStore(UUID store, String name) throws Exception {
        String current = mvc.perform(get("/api/v1/backoffice/stores/" + store).with(admin(Permission.STORE_MANAGE)))
                .andReturn().getResponse().getContentAsString();
        var json = FiscalFixtures.JSON.readTree(current);
        mvc.perform(put("/api/v1/backoffice/stores/" + store).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "timezone": "Africa/Maputo", "businessDayCutoff": "05:00",
                                 "active": true, "version": %d}""".formatted(json.get("code").asString(), name,
                                json.get("version").asLong())))
                .andExpect(status().isOk());
    }
}
