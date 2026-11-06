package com.xavier.backend.fiscal.service;

import static com.xavier.backend.fiscal.FiscalActors.admin;
import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static com.xavier.backend.fiscal.FiscalFixtures.draft;
import static com.xavier.backend.fiscal.FiscalFixtures.mzn;
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
import com.xavier.backend.fiscal.model.dto.DocumentFacts;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.shared.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** FiscalApi.documentFacts and issuedDocumentIds: what reporting pulls (D84) and backfills by business day (D85). */
@FiscalModuleTest
class DocumentFactsTest {

    /** Far from today, so no document issued now (an R2 fallback) can fall on it. */
    static final LocalDate DAY = LocalDate.of(2020, 3, 10);

    @Autowired
    FiscalApi fiscal;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    OrganizationApi organization;

    @Autowired
    MockMvc mvc;

    @Test
    void theFactsOfAnIssuedPartitionDocumentOnAccount() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "R", 7);
        UUID partition = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        UUID draftId = create(withDay(draft(store, SourceType.PARTITION, partition, order, null, customer, true),
                DAY));
        fixtures.recordPaid(draftId);
        DocumentView issued = asEmployee(store, employee, terminal, () -> fiscal.issue(draftId, employee));

        DocumentFacts facts = fiscal.documentFacts(issued.id());

        assertThat(facts.documentId()).isEqualTo(issued.id());
        assertThat(facts.storeId()).isEqualTo(store);
        assertThat(facts.status()).isEqualTo("ISSUED");
        assertThat(facts.sourceType()).isEqualTo("PARTITION");
        assertThat(facts.sourceId()).isEqualTo(partition);
        assertThat(facts.orderId()).isEqualTo(order);
        assertThat(facts.businessDate()).isEqualTo(DAY);
        assertThat(facts.issuedAt()).isEqualTo(issued.issuedAt());
        assertThat(facts.terminalId()).isEqualTo(terminal);
        assertThat(facts.issuedByEmployeeId()).isEqualTo(employee);
        assertThat(facts.customerId()).isEqualTo(customer);
        assertThat(facts.onAccount()).isTrue();
        assertThat(facts.seriesCode()).isEqualTo("R");
        assertThat(facts.number()).isEqualTo(7L);
        assertThat(facts.nonFiscal()).isTrue();
        assertThat(facts.totalGross()).isEqualTo(mzn("116.00"));
        assertThat(facts.totalNet()).isEqualTo(mzn("100.00"));
        assertThat(facts.totalTax()).isEqualTo(mzn("16.00"));
        assertThat(facts.lines()).singleElement().satisfies(line -> {
            assertThat(line.sourceLineId()).isNotNull();
            assertThat(line.quantity()).isEqualByComparingTo("2");
        });
        assertThat(facts.taxes()).singleElement()
                .satisfies(tax -> assertThat(tax.rate()).isEqualByComparingTo(new BigDecimal("16")));
    }

    @Test
    void anR2DocumentFallsBackToTheBusinessDayOfItsIssue() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        DocumentView issued = issue(store, create(saleDraft(store, UUID.randomUUID(), null).toDraftCommand()));
        StoreSummary summary = organization.findStore(store).orElseThrow();

        assertThat(fiscal.documentFacts(issued.id()).businessDate())
                .isEqualTo(BusinessDays.of(issued.issuedAt(), summary.timezone(), summary.businessDayCutoff()));
    }

    @Test
    void anR2FallbackUsesTheStoresCurrentZoneInFactsAndInTheBackfill() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        DocumentView issued = issue(store, create(saleDraft(store, UUID.randomUUID(), null).toDraftCommand()));
        StoreSummary before = organization.findStore(store).orElseThrow();
        LocalDate snapshotDay = BusinessDays.of(issued.issuedAt(), before.timezone(), before.businessDayCutoff());
        // +12 h and -13 h from Maputo are 25 h apart, so one of them is another business day
        ZoneId moved = Stream.of("Pacific/Kiritimati", "Pacific/Pago_Pago").map(ZoneId::of)
                .filter(z -> !BusinessDays.of(issued.issuedAt(), z, before.businessDayCutoff()).equals(snapshotDay))
                .findFirst().orElseThrow();
        LocalDate currentDay = BusinessDays.of(issued.issuedAt(), moved, before.businessDayCutoff());

        moveStore(store, moved);

        assertThat(fiscal.documentFacts(issued.id()).businessDate()).isEqualTo(currentDay);
        assertThat(ids(store, currentDay, currentDay)).containsExactly(issued.id());
    }

    @Test
    void anUnknownDocumentIsNotFound() {
        assertThatThrownBy(() -> fiscal.documentFacts(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void issuedDocumentIdsListsTheStoresIssuedDocumentsOfTheBusinessDays() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        UUID onDay = issue(store, create(order(store, DAY))).id();
        UUID nextDay = issue(store, create(order(store, DAY.plusDays(1)))).id();
        UUID r2 = issue(store, create(saleDraft(store, UUID.randomUUID(), null).toDraftCommand())).id();
        create(order(store, DAY)); // a DRAFT is no fact yet
        UUID otherStore = fixtures.newStore();
        fixtures.newSeries(otherStore, "A", 1);
        issue(otherStore, create(order(otherStore, DAY)));
        LocalDate r2Day = fiscal.documentFacts(r2).businessDate();

        assertThat(ids(store, DAY, DAY)).containsExactly(onDay);
        assertThat(ids(store, DAY, DAY.plusDays(1))).containsExactly(onDay, nextDay);
        assertThat(ids(store, r2Day, r2Day)).containsExactly(r2);
        Page<UUID> firstPage = fiscal.issuedDocumentIds(store, DAY, r2Day, PageRequest.of(0, 2));
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getContent()).hasSize(2);
    }

    private void moveStore(UUID store, ZoneId zone) throws Exception {
        var json = FiscalFixtures.JSON.readTree(mvc.perform(get("/api/v1/backoffice/stores/" + store)
                .with(admin(Permission.STORE_MANAGE))).andReturn().getResponse().getContentAsString());
        mvc.perform(put("/api/v1/backoffice/stores/" + store).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "timezone": "%s", "businessDayCutoff": "05:00",
                                 "active": true, "version": %d}""".formatted(json.get("code").asString(),
                                json.get("name").asString(), zone.getId(), json.get("version").asLong())))
                .andExpect(status().isOk());
    }

    private List<UUID> ids(UUID store, LocalDate from, LocalDate to) {
        return fiscal.issuedDocumentIds(store, from, to, PageRequest.of(0, 50)).getContent();
    }

    private static DraftCommand order(UUID store, LocalDate day) {
        UUID order = UUID.randomUUID();
        return withDay(draft(store, SourceType.ORDER, order, order, null, null, false), day);
    }

    private static DraftCommand withDay(DraftCommand c, LocalDate day) {
        return new DraftCommand(c.storeId(), c.sourceType(), c.sourceId(), c.orderId(), day, c.buyer(),
                c.customerId(), c.onAccount(), c.lines(), c.totalGross(), c.totalNet(), c.totalTax());
    }

    private UUID create(DraftCommand command) {
        return tx.execute(s -> fiscal.createDraft(command)).id();
    }

    private DocumentView issue(UUID store, UUID draft) {
        fixtures.recordPaid(draft);
        UUID employee = UUID.randomUUID();
        return asEmployee(store, employee, UUID.randomUUID(), () -> fiscal.issue(draft, employee));
    }
}
