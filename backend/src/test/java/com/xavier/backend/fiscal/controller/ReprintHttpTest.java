package com.xavier.backend.fiscal.controller;

import static com.xavier.backend.fiscal.FiscalActors.admin;
import static com.xavier.backend.fiscal.FiscalActors.pos;
import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalModuleTest;
import com.xavier.backend.fiscal.adapter.ActiveFiscalAdapter;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.event.FiscalDocumentIssued;
import com.xavier.backend.fiscal.model.event.FiscalDocumentReprintRequested;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Reprint as a copy (spec §8.4, A10 reprint half): the next copy number, a reprint event for production, an audit
 * row, and no new document. Every printout of the internal adapter carries the non-fiscal mark (A13).
 */
@FiscalModuleTest
class ReprintHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    FiscalApi fiscal;

    @Autowired
    JdbcClient jdbc;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();

    @Test
    void issuingPublishesTheOriginalPrintoutForTheIssuingTerminalMarkedNonFiscal(AssertablePublishedEvents events)
            throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);

        DocumentView issued = issue(store, fixtures.newDraft(store));

        assertThat(events.ofType(FiscalDocumentIssued.class)
                .matching(e -> e.documentId().equals(issued.id()))).singleElement().satisfies(e -> {
                    assertThat(e.storeId()).isEqualTo(store);
                    assertThat(e.terminalId()).isEqualTo(terminal);
                    assertThat(e.printout().lines()).startsWith(ActiveFiscalAdapter.NON_FISCAL_MARK)
                            .endsWith(ActiveFiscalAdapter.NON_FISCAL_MARK)
                            .contains("Documento de venda A/1").doesNotContain("CÓPIA");
                });
    }

    @Test
    void aReprintIsTheNextCopyPublishedAuditedAndCreatesNoDocument(AssertablePublishedEvents events)
            throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        DocumentView issued = issue(store, fixtures.newDraft(store));
        long documentsBefore = documentsOf(store);

        reprint(store, issued.id(), "k-" + UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").value(issued.id().toString()))
                .andExpect(jsonPath("$.copyNo").value(1));
        reprint(store, issued.id(), "k-" + UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.copyNo").value(2));

        assertThat(events.ofType(FiscalDocumentReprintRequested.class)
                .matching(e -> e.documentId().equals(issued.id())))
                .extracting(FiscalDocumentReprintRequested::copyNo).containsExactly(1, 2);
        assertThat(events.ofType(FiscalDocumentReprintRequested.class)
                .matching(e -> e.documentId().equals(issued.id()))).allSatisfy(e -> {
                    assertThat(e.storeId()).isEqualTo(store);
                    assertThat(e.terminalId()).isEqualTo(terminal);
                    assertThat(e.printout().lines()).contains("CÓPIA").startsWith(ActiveFiscalAdapter.NON_FISCAL_MARK);
                });
        assertThat(fixtures.audits("FISCAL_DOCUMENT_REPRINTED", issued.id().toString())).isEqualTo(2);
        assertThat(documentsOf(store)).isEqualTo(documentsBefore);
        assertThat(copiesOf(issued.id())).isEqualTo(2);
    }

    @Test
    void repeatingAReprintWithTheSameKeyReplaysIt() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        DocumentView issued = issue(store, fixtures.newDraft(store));
        String key = "k-" + UUID.randomUUID();

        reprint(store, issued.id(), key).andExpect(status().isCreated()).andExpect(jsonPath("$.copyNo").value(1));
        reprint(store, issued.id(), key).andExpect(status().isCreated()).andExpect(jsonPath("$.copyNo").value(1));

        assertThat(copiesOf(issued.id())).isEqualTo(1);
    }

    @Test
    void aDraftCannotBeReprinted() throws Exception {
        UUID store = fixtures.newStore();

        reprint(store, fixtures.newDraft(store), "k-" + UUID.randomUUID()).andExpect(status().isConflict());
    }

    @Test
    void reprintingNeedsThePermissionTheSessionStoreAndAKey() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        UUID document = issue(store, fixtures.newDraft(store)).id();
        String path = PosDocumentController.PATH + "/" + document + "/reprints";

        mvc.perform(post(path).header("Idempotency-Key", "k-" + UUID.randomUUID()).with(csrf())
                        .with(pos(store, employee, terminal, Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).header("Idempotency-Key", "k-" + UUID.randomUUID()).with(csrf())
                        .with(pos(fixtures.newStore(), employee, terminal, Permission.FISCAL_DOCUMENT_REPRINT)))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).header("Idempotency-Key", "k-" + UUID.randomUUID()).with(csrf())
                        .with(admin(Permission.FISCAL_DOCUMENT_REPRINT)))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf()).with(pos(store, employee, terminal, Permission.FISCAL_DOCUMENT_REPRINT)))
                .andExpect(status().isBadRequest());
        reprint(store, UUID.randomUUID(), "k-" + UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(copiesOf(document)).isZero();
    }

    private ResultActions reprint(UUID store, UUID document, String key) throws Exception {
        return mvc.perform(post(PosDocumentController.PATH + "/" + document + "/reprints")
                .header("Idempotency-Key", key).with(csrf())
                .with(pos(store, employee, terminal, Permission.FISCAL_DOCUMENT_REPRINT)));
    }

    private DocumentView issue(UUID store, UUID draft) {
        return asEmployee(store, employee, terminal, () -> fiscal.issue(draft, employee));
    }

    private long documentsOf(UUID store) {
        return jdbc.sql("SELECT count(*) FROM fiscal.fiscal_document WHERE store_id = :store")
                .param("store", store).query(Long.class).single();
    }

    private long copiesOf(UUID document) {
        return jdbc.sql("SELECT count(*) FROM fiscal.fiscal_document_print WHERE document_id = :id")
                .param("id", document).query(Long.class).single();
    }
}
