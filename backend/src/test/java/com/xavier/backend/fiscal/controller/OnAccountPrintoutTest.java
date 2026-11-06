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
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot;
import com.xavier.backend.fiscal.model.dto.PaymentStatus;
import com.xavier.backend.shared.Money;
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

/**
 * A credit sale (A20, document half) is issued on account. D97 rule 5: only a document wholly on account (ON_ACCOUNT)
 * prints the "A CRÉDITO" header, on its original and its copies; a MIXED one shows its breakdown instead.
 */
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
    void theInternalAdapterPrintsOnAccountOnlyForAWholeCreditSale() {
        Money zero = FiscalFixtures.mzn("0.00");
        PaymentSnapshot whole = new PaymentSnapshot(PaymentStatus.ON_ACCOUNT, List.of(),
                FiscalFixtures.mzn("116.00"), zero);
        PaymentSnapshot mixed = new PaymentSnapshot(PaymentStatus.MIXED,
                List.of(FiscalFixtures.cash(FiscalFixtures.mzn("16.00"))), FiscalFixtures.mzn("100.00"), zero);

        assertThat(adapter.printout(InternalFiscalAdapterTest.document(), false).lines()).doesNotContain(ON_ACCOUNT);
        assertThat(adapter.printout(onAccount(InternalFiscalAdapterTest.document(), whole), false).lines())
                .contains(ON_ACCOUNT, "Pagamento — A crédito: 116.00");
        assertThat(adapter.printout(onAccount(InternalFiscalAdapterTest.document(), mixed), false).lines())
                .doesNotContain(ON_ACCOUNT).contains("Pagamento misto — Numerário: 16.00; A crédito: 100.00");
        // a document issued before D97 has no snapshot and prints as it did
        assertThat(adapter.printout(onAccount(InternalFiscalAdapterTest.document(), null), false).lines())
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

        fixtures.recordPaid(draftId); // wholly on account (D97)
        DocumentView issued = asEmployee(store, employee, terminal, () -> fiscal.issue(draftId, employee));
        mvc.perform(post(PosDocumentController.PATH + "/" + issued.id() + "/reprints")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()).with(csrf())
                        .with(pos(store, employee, terminal, Permission.FISCAL_DOCUMENT_REPRINT)))
                .andExpect(status().isCreated());

        assertThat(issued.onAccount()).isTrue();
        verify(adapter).issue(argThat(request -> request.documentId().equals(draftId) && request.onAccount()));
        assertThat(events.ofType(FiscalDocumentIssued.class).matching(e -> e.documentId().equals(issued.id())))
                .singleElement().satisfies(e -> assertThat(e.printout().lines())
                        .contains(ON_ACCOUNT, "Cliente: Cliente Fiel", "Pagamento — A crédito: 116.00"));
        assertThat(events.ofType(FiscalDocumentReprintRequested.class)
                .matching(e -> e.documentId().equals(issued.id())))
                .singleElement().satisfies(e -> assertThat(e.printout().lines())
                        .contains(ON_ACCOUNT, "CÓPIA", "Pagamento — A crédito: 116.00"));
    }

    @Test
    void aPaidDocumentDoesNotSayOnAccount(AssertablePublishedEvents events) throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);

        DocumentView issued = asEmployee(store, employee, terminal,
                () -> fiscal.issue(fixtures.newPaidDraft(store), employee));

        assertThat(issued.onAccount()).isFalse();
        assertThat(events.ofType(FiscalDocumentIssued.class).matching(e -> e.documentId().equals(issued.id())))
                .singleElement().satisfies(e -> assertThat(e.printout().lines()).doesNotContain(ON_ACCOUNT));
    }

    private static IssuedDocument onAccount(IssuedDocument d, PaymentSnapshot payments) {
        return new IssuedDocument(d.id(), d.documentType(), d.seriesCode(), d.number(),
                d.issuedAt(), d.storeZone(), d.companyName(), d.companyTaxId(), d.companyCountry(), d.storeCode(),
                d.storeName(), d.buyer(), List.copyOf(d.lines()), List.copyOf(d.taxes()), d.totalGross(),
                d.totalNet(), d.totalTax(), d.adapterData(), d.qrPayload(), true, payments);
    }
}
