package com.xavier.backend.fiscal.controller;

import static com.xavier.backend.fiscal.FiscalActors.admin;
import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static com.xavier.backend.fiscal.FiscalActors.pos;
import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalModuleTest;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@FiscalModuleTest
class DocumentHttpTest {

    static final String DOCUMENTS = "/api/v1/backoffice/fiscal/documents";
    static final String POS_DOCUMENTS = "/api/v1/pos/documents";

    @Autowired
    MockMvc mvc;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    FiscalApi fiscal;

    @Test
    void aStoreViewerListsTheIssuedDocumentsOfItsStoreInATimeWindow() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        DocumentView issued = issue(store, fixtures.newDraft(store));
        fixtures.newDraft(store); // a draft has no issue time and is not listed

        mvc.perform(get(DOCUMENTS).param("storeId", store.toString())
                        .param("from", issued.issuedAt().minusSeconds(60).toString())
                        .param("to", issued.issuedAt().plusSeconds(60).toString())
                        .with(inStore(store, Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(issued.id().toString()))
                .andExpect(jsonPath("$.content[0].number").value(1))
                .andExpect(jsonPath("$.content[0].seriesCode").value("A"))
                .andExpect(jsonPath("$.content[0].totalGross.amount").value(116.00));
        mvc.perform(get(DOCUMENTS).param("storeId", store.toString())
                        .param("from", issued.issuedAt().plusSeconds(1).toString())
                        .with(inStore(store, Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void listingAnotherStoresDocumentsIsForbidden() throws Exception {
        UUID mine = fixtures.newStore();

        mvc.perform(get(DOCUMENTS).param("storeId", fixtures.newStore().toString())
                        .with(inStore(mine, Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aBackOfficeViewerReadsADocumentWithItsLinesAndTaxSummary() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        DocumentView issued = issue(store, fixtures.newDraft(store));

        mvc.perform(get(DOCUMENTS + "/" + issued.id()).with(admin(Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.companyName").value(issued.companyName()))
                .andExpect(jsonPath("$.lines[0].description").value("Cerveja 2M"))
                .andExpect(jsonPath("$.taxes[0].tax.amount").value(16.00))
                .andExpect(jsonPath("$.nonFiscal").value(true));
    }

    @Test
    void aDocumentOfAnotherStoreIsForbiddenAndAnUnknownOneIsNotFound() throws Exception {
        UUID store = fixtures.newStore();
        UUID draft = fixtures.newDraft(store);
        UUID other = fixtures.newStore();

        mvc.perform(get(DOCUMENTS + "/" + draft).with(inStore(other, Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(DOCUMENTS + "/" + UUID.randomUUID()).with(admin(Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePosReadsDocumentsOfItsSessionStoreOnly() throws Exception {
        UUID store = fixtures.newStore();
        UUID draft = fixtures.newDraft(store);
        UUID other = fixtures.newStore();

        mvc.perform(get(POS_DOCUMENTS + "/" + draft)
                        .with(pos(store, UUID.randomUUID(), UUID.randomUUID(), Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(draft.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(get(POS_DOCUMENTS + "/" + draft)
                        .with(pos(other, UUID.randomUUID(), UUID.randomUUID(), Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(POS_DOCUMENTS + "/" + UUID.randomUUID())
                        .with(pos(store, UUID.randomUUID(), UUID.randomUUID(), Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePosNeedsAPosSessionWithDocumentView() throws Exception {
        UUID store = fixtures.newStore();
        UUID draft = fixtures.newDraft(store);

        mvc.perform(get(POS_DOCUMENTS + "/" + draft).with(admin(Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(POS_DOCUMENTS + "/" + draft)
                        .with(pos(store, UUID.randomUUID(), UUID.randomUUID(), Permission.ORDER_EDIT)))
                .andExpect(status().isForbidden());
    }

    private DocumentView issue(UUID store, UUID draft) {
        UUID employee = UUID.randomUUID();
        return asEmployee(store, employee, UUID.randomUUID(), () -> fiscal.issue(draft, employee));
    }

}
