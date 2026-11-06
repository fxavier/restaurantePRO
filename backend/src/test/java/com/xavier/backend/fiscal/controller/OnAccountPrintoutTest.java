package com.xavier.backend.fiscal.controller;

import static com.xavier.backend.fiscal.FiscalActors.pos;
import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static com.xavier.backend.fiscal.FiscalFixtures.draft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalModuleTest;
import com.xavier.backend.fiscal.IssuedDocument;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapterTest;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.fiscal.model.event.FiscalDocumentIssued;
import com.xavier.backend.fiscal.model.event.FiscalDocumentReprintRequested;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** A credit sale (A20, document half) is issued on account and every printout of it, original or copy, says so. */
@FiscalModuleTest
class OnAccountPrintoutTest {

    static final String ON_ACCOUNT = "A CRÉDITO";

    @Autowired
    FiscalApi fiscal;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MockMvc mvc;

    @Autowired
    InternalFiscalAdapter adapter;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();

    @Test
    void theInternalAdapterPrintsOnAccountOnlyForACreditSale() {
        assertThat(adapter.printout(InternalFiscalAdapterTest.document(), false).lines()).doesNotContain(ON_ACCOUNT);
        assertThat(adapter.printout(onAccount(InternalFiscalAdapterTest.document()), false).lines())
                .contains(ON_ACCOUNT);
    }

    @Test
    void anOnAccountDocumentIsIssuedAsSuchAndItsOriginalAndCopiesSayOnAccount(AssertablePublishedEvents events)
            throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        UUID order = UUID.randomUUID();
        UUID draftId = tx.execute(s -> fiscal.createDraft(draft(store, SourceType.ORDER, order, order,
                new Buyer("Cliente Fiel", "400111222", null), UUID.randomUUID(), true))).id();

        DocumentView issued = asEmployee(store, employee, terminal, () -> fiscal.issue(draftId, employee));
        mvc.perform(post(PosDocumentController.PATH + "/" + issued.id() + "/reprints")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()).with(csrf())
                        .with(pos(store, employee, terminal, Permission.FISCAL_DOCUMENT_REPRINT)))
                .andExpect(status().isCreated());

        assertThat(issued.onAccount()).isTrue();
        verify(adapter).issue(argThat(request -> request.documentId().equals(draftId) && request.onAccount()));
        assertThat(events.ofType(FiscalDocumentIssued.class).matching(e -> e.documentId().equals(issued.id())))
                .singleElement().satisfies(e -> assertThat(e.printout().lines())
                        .contains(ON_ACCOUNT, "Cliente: Cliente Fiel"));
        assertThat(events.ofType(FiscalDocumentReprintRequested.class)
                .matching(e -> e.documentId().equals(issued.id())))
                .singleElement().satisfies(e -> assertThat(e.printout().lines()).contains(ON_ACCOUNT, "CÓPIA"));
    }

    @Test
    void aPaidDocumentDoesNotSayOnAccount(AssertablePublishedEvents events) throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);

        DocumentView issued = asEmployee(store, employee, terminal,
                () -> fiscal.issue(fixtures.newDraft(store), employee));

        assertThat(issued.onAccount()).isFalse();
        assertThat(events.ofType(FiscalDocumentIssued.class).matching(e -> e.documentId().equals(issued.id())))
                .singleElement().satisfies(e -> assertThat(e.printout().lines()).doesNotContain(ON_ACCOUNT));
    }

    private static IssuedDocument onAccount(IssuedDocument d) {
        return new IssuedDocument(d.id(), d.documentType(), d.seriesCode(), d.number(),
                d.issuedAt(), d.storeZone(), d.companyName(), d.companyTaxId(), d.companyCountry(), d.storeCode(),
                d.storeName(), d.buyer(), List.copyOf(d.lines()), List.copyOf(d.taxes()), d.totalGross(),
                d.totalNet(), d.totalTax(), d.adapterData(), d.qrPayload(), true, null);
    }
}
