package com.xavier.backend.fiscal.service;

import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalIssueFailedException;
import com.xavier.backend.fiscal.FiscalModuleTest;
import com.xavier.backend.fiscal.IssueRequest;
import com.xavier.backend.shared.Money;
import java.time.OffsetDateTime;
import java.util.Currency;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * A9 (D50 §7.6): numbers are taken under the series lock inside the issuing transaction, so concurrent issues get
 * consecutive numbers and a failed issue gives its number back. No database sequence is involved.
 */
@FiscalModuleTest
class GaplessNumberingTest {

    static final int ISSUES = 8;

    @Autowired
    FiscalApi fiscal;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    InternalFiscalAdapter adapter; // a Mockito spy (FiscalModuleTest), reset after each test

    @Test
    void concurrentIssuesOnOneSeriesGetConsecutiveNumbersWithNoGapsAndNoDuplicates() throws Exception {
        UUID store = fixtures.newStore();
        UUID series = fixtures.newSeries(store, "A", 1);
        List<UUID> drafts = new ArrayList<>();
        for (int i = 0; i < ISSUES; i++) {
            drafts.add(fixtures.newDraft(store));
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Long>> issues = drafts.stream().<Callable<Long>>map(draft -> () -> {
            start.await();
            return issueAsEmployee(store, draft).number();
        }).toList();
        List<Long> numbers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(ISSUES)) {
            List<Future<Long>> futures = issues.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Long> future : futures) {
                numbers.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(numbers).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, ISSUES).boxed().toList());
        assertThat(fixtures.nextNumber(series)).isEqualTo(ISSUES + 1);
        assertThat(fiscal.countDrafts(store)).isZero();
    }

    @Test
    void aFailedIssueRecordsTheErrorApartAndGivesItsNumberBack() throws Exception {
        UUID store = fixtures.newStore();
        UUID series = fixtures.newSeries(store, "A", 41);
        UUID first = fixtures.newDraft(store);
        UUID second = fixtures.newDraft(store);
        doThrow(new IllegalStateException("fiscal adapter offline")).doCallRealMethod().when(adapter).issue(any());

        assertThatThrownBy(() -> issueAsEmployee(store, first))
                .isInstanceOfSatisfying(FiscalIssueFailedException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.getBody().getProperties()).containsEntry("code", "FISCAL_PENDING")
                            .containsEntry("documentId", first);
                    assertThat(e.getDocumentId()).isEqualTo(first);
                });

        DocumentView pending = fiscal.saleDocumentFor(sourceOf(first)).orElseThrow();
        assertThat(pending.status()).isEqualTo("DRAFT");
        assertThat(pending.number()).isNull();
        assertThat(pending.lastError()).isEqualTo("Technical failure (IllegalStateException); see the server log");
        assertThat(pending.lastErrorAt()).isNotNull();
        assertThat(fiscal.countDrafts(store)).isEqualTo(2);
        assertThat(fixtures.nextNumber(series)).isEqualTo(41);

        assertThat(issueAsEmployee(store, first).number()).isEqualTo(41L);
        assertThat(issueAsEmployee(store, second).number()).isEqualTo(42L);
        assertThat(fiscal.saleDocumentFor(sourceOf(first)).orElseThrow().lastError()).isNull();
    }

    @Test
    void aTechnicalFailureIsRecordedWithoutItsSqlDetail() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        UUID draft = fixtures.newDraft(store);
        doThrow(new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"fiscal_document_number_uk\""))
                .when(adapter).issue(any());

        assertThatThrownBy(() -> issueAsEmployee(store, draft)).isInstanceOf(FiscalIssueFailedException.class);

        assertThat(fiscal.saleDocumentFor(sourceOf(draft)).orElseThrow().lastError())
                .isEqualTo("Technical failure (DataIntegrityViolationException); see the server log")
                .doesNotContain("constraint");
    }

    @Test
    void issuingInsideTheCallersTransactionIsRefusedBeforeAnythingHappens() throws Exception {
        UUID store = fixtures.newStore();
        UUID series = fixtures.newSeries(store, "A", 1);
        UUID draft = fixtures.newDraft(store);
        UUID employee = UUID.randomUUID();

        assertThatThrownBy(() -> asEmployee(store, employee, UUID.randomUUID(),
                () -> tx.execute(status -> fiscal.issue(draft, employee))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit");

        DocumentView untouched = fiscal.saleDocumentFor(sourceOf(draft)).orElseThrow();
        assertThat(untouched.status()).isEqualTo("DRAFT");
        assertThat(untouched.lastError()).isNull();
        assertThat(fixtures.nextNumber(series)).isEqualTo(1);
    }

    @Test
    void issuingAnIssuedDocumentAgainReturnsItWithoutTakingANumber() throws Exception {
        UUID store = fixtures.newStore();
        UUID series = fixtures.newSeries(store, "A", 1);
        UUID draft = fixtures.newDraft(store);
        DocumentView issued = issueAsEmployee(store, draft);

        DocumentView again = issueAsEmployee(store, draft);

        assertThat(again.number()).isEqualTo(issued.number());
        assertThat(again.issuedAt()).isEqualTo(issued.issuedAt());
        assertThat(fixtures.nextNumber(series)).isEqualTo(2);
    }

    @Test
    void withoutAnActiveSeriesTheDraftStaysPending() throws Exception {
        UUID store = fixtures.newStore();
        UUID draft = fixtures.newDraft(store);

        assertThatThrownBy(() -> issueAsEmployee(store, draft)).isInstanceOf(FiscalIssueFailedException.class);

        assertThat(fiscal.saleDocumentFor(sourceOf(draft)).orElseThrow().lastError()).contains("active");
    }

    @Test
    void theIssuedDocumentIsStampedAndChainedToThePreviousNumber() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "B", 1);
        UUID employee = UUID.randomUUID();
        DocumentView one = issueAsEmployee(store, fixtures.newDraft(store));
        DocumentView two = asEmployee(store, employee, UUID.randomUUID(),
                () -> fiscal.issue(fixtures.newDraft(store), employee));

        assertThat(two.status()).isEqualTo("ISSUED");
        assertThat(two.seriesCode()).isEqualTo("B");
        assertThat(two.issuedByEmployeeId()).isEqualTo(employee);
        assertThat(two.adapterCode()).isEqualTo("internal");
        assertThat(two.nonFiscal()).isTrue();
        assertThat(fixtures.adapterData(two.id())).isNotEqualTo(fixtures.adapterData(one.id()))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void theStoredChainLinkCanBeRecomputedFromTheStoredRowsAlone() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "C", 1);
        UUID first = issueAsEmployee(store, fixtures.newDraft(store)).id();
        UUID second = issueAsEmployee(store, fixtures.newDraft(store)).id();

        IssueRequest fromStoredRow = jdbc.sql("""
                        SELECT document_type, series_code, number, issued_at, total_gross, currency
                        FROM fiscal.fiscal_document WHERE id = :id""").param("id", second)
                .query((rs, n) -> new IssueRequest(second, rs.getString("document_type"),
                        rs.getString("series_code"), rs.getLong("number"),
                        rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                        Money.of(rs.getBigDecimal("total_gross"), Currency.getInstance(rs.getString("currency"))),
                        null, fixtures.adapterData(first)))
                .single();

        assertThat(new InternalFiscalAdapter().issue(fromStoredRow).adapterData())
                .isEqualTo(fixtures.adapterData(second));
    }

    private DocumentView issueAsEmployee(UUID store, UUID draft) {
        UUID employee = UUID.randomUUID();
        return asEmployee(store, employee, UUID.randomUUID(), () -> fiscal.issue(draft, employee));
    }

    private UUID sourceOf(UUID document) {
        return fixtures.sourceOf(document);
    }
}
