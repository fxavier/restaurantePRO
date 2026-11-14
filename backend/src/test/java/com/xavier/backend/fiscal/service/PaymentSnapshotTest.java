package com.xavier.backend.fiscal.service;

import com.xavier.backend.OwnerDatabase;
import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static com.xavier.backend.fiscal.FiscalFixtures.cash;
import static com.xavier.backend.fiscal.FiscalFixtures.draft;
import static com.xavier.backend.fiscal.FiscalFixtures.mzn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalIssueFailedException;
import com.xavier.backend.fiscal.FiscalModuleTest;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.fiscal.exception.PaymentSnapshotMismatchException;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot.PaidAmount;
import com.xavier.backend.fiscal.model.dto.PaymentStatus;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.fiscal.model.event.FiscalDocumentIssued;
import com.xavier.backend.shared.exception.CodedException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D97 rule 4, fiscal half: settlement records the payment snapshot on the DRAFT, issue freezes it (the V10005 trigger
 * guards the header and the rows), the CHECKs hold the D97 table, and a DRAFT without a snapshot is never numbered.
 * Every draft is 116.00 (FiscalFixtures).
 */
@FiscalModuleTest
class PaymentSnapshotTest {

    static final PaymentSnapshot UNPAID = new PaymentSnapshot(PaymentStatus.UNPAID, List.of(), mzn("0.00"),
            mzn("116.00"));
    static final PaymentSnapshot PARTIAL = new PaymentSnapshot(PaymentStatus.PARTIALLY_PAID,
            List.of(cash(mzn("16.00"))), mzn("0.00"), mzn("100.00"));
    static final PaymentSnapshot PAID = new PaymentSnapshot(PaymentStatus.PAID,
            List.of(cash(mzn("16.00")), new PaidAmount("CARD", "Cartão", mzn("100.00"))), mzn("0.00"),
            mzn("0.00"));

    @Autowired
    FiscalApi fiscal;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    InternalFiscalAdapter adapter;

    final UUID employee = UUID.randomUUID();

    @Test
    void aDraftRecordsItsSnapshotAndARefreshReplacesIt() throws Exception {
        UUID draft = fixtures.newDraft(fixtures.newStore());

        record(draft, PARTIAL);
        assertThat(fiscal.paymentSnapshot(draft)).contains(PARTIAL);

        record(draft, PAID);
        assertThat(fiscal.paymentSnapshot(draft)).contains(PAID);
        assertThat(rows(draft)).isEqualTo(2);

        record(draft, UNPAID);
        assertThat(fiscal.paymentSnapshot(draft)).contains(UNPAID);
        assertThat(rows(draft)).isZero();
    }

    @Test
    void issueFreezesTheSnapshotAndPrintsIt(AssertablePublishedEvents events) throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        UUID draft = fixtures.newDraft(store);
        record(draft, PAID);

        DocumentView issued = issue(store, draft);
        PaymentSnapshot afterIssue = tx.execute(s -> fiscal.recordPayments(draft, UNPAID));

        assertThat(issued.payments()).isEqualTo(PAID);
        assertThat(afterIssue).isEqualTo(PAID);
        assertThat(fiscal.paymentSnapshot(draft)).contains(PAID);
        verify(adapter).issue(argThat(request -> request.documentId().equals(draft)
                && PAID.equals(request.payments())));
        assertThat(events.ofType(FiscalDocumentIssued.class).matching(e -> e.documentId().equals(draft)))
                .singleElement().satisfies(e -> assertThat(e.printout().lines())
                        .contains("Pagamento misto — Numerário: 16.00; Cartão: 100.00"));
    }

    @Test
    void theDatabaseRefusesToChangeTheSnapshotOfAnIssuedDocument() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        UUID draft = fixtures.newDraft(store);
        record(draft, PAID);
        issue(store, draft);

        assertRefused("UPDATE fiscal.fiscal_document SET payment_status = 'UNPAID', paid_total = 0, "
                + "outstanding_total = 116 WHERE id = :id", draft);
        assertRefused("UPDATE fiscal.fiscal_document_payment SET amount = 1 WHERE document_id = :id", draft);
        assertRefused("DELETE FROM fiscal.fiscal_document_payment WHERE document_id = :id", draft);
        assertRefused("""
                INSERT INTO fiscal.fiscal_document_payment (document_id, position, method_code, label, amount)
                VALUES (:id, 9, 'CASH', 'Numerário', 1)""", draft);
        // as the owner (the pool has no TRUNCATE privilege), always rolled back
        assertThatThrownBy(() -> OwnerDatabase.shared().rolledBack(owner ->
                owner.sql("TRUNCATE fiscal.fiscal_document_payment").update()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("TRUNCATE");
    }

    @Test
    void theChecksHoldTheD97Table() throws Exception {
        UUID draft = fixtures.newDraft(fixtures.newStore());

        // all or nothing
        assertRefused("UPDATE fiscal.fiscal_document SET payment_status = 'PAID' WHERE id = :id", draft);
        // the breakdown is the whole gross
        assertRefused(snapshotSql("PAID", "100", "0", "0"), draft);
        // credit only on account
        assertRefused(snapshotSql("MIXED", "16", "100", "0"), draft);
        // the status follows the totals
        assertRefused(snapshotSql("PAID", "16", "0", "100"), draft);
        assertRefused(snapshotSql("UNPAID", "116", "0", "0"), draft);
        assertRefused(snapshotSql("PARTIALLY_PAID", "116", "0", "0"), draft);
        assertRefused(snapshotSql("PAID", "-1", "0", "117"), draft);
        assertRefused("""
                INSERT INTO fiscal.fiscal_document_payment (document_id, position, method_code, label, amount)
                VALUES (:id, 1, 'CASH', 'Numerário', 0)""", draft);
        assertThat(jdbc.sql(snapshotSql("PARTIALLY_PAID", "16", "0", "100")).param("id", draft).update())
                .isEqualTo(1);

        UUID onAccount = onAccountDraft();
        assertRefused(snapshotSql("PAID", "116", "0", "0"), onAccount);
        assertRefused(snapshotSql("MIXED", "0", "116", "0"), onAccount);
        assertRefused(snapshotSql("ON_ACCOUNT", "16", "100", "0"), onAccount);
        assertThat(jdbc.sql(snapshotSql("MIXED", "0", "100", "16")).param("id", onAccount).update()).isEqualTo(1);
        assertThat(jdbc.sql(snapshotSql("ON_ACCOUNT", "0", "116", "0")).param("id", onAccount).update())
                .isEqualTo(1);
    }

    @Test
    void aSnapshotThatDoesNotFitTheDraftIsRefused() throws Exception {
        UUID draft = fixtures.newDraft(fixtures.newStore());
        UUID onAccount = onAccountDraft();

        assertMismatch(draft, new PaymentSnapshot(PaymentStatus.PAID, List.of(cash(mzn("100.00"))), mzn("0.00"),
                mzn("0.00")));
        assertMismatch(draft, new PaymentSnapshot(PaymentStatus.MIXED, List.of(cash(mzn("16.00"))), mzn("100.00"),
                mzn("0.00")));
        assertMismatch(draft, new PaymentSnapshot(PaymentStatus.PAID, List.of(cash(mzn("16.00"))), mzn("0.00"),
                mzn("100.00")));
        assertMismatch(onAccount, PAID);
        assertThat(fiscal.paymentSnapshot(draft)).isEmpty();
        assertThat(fiscal.paymentSnapshot(onAccount)).isEmpty();
    }

    @Test
    void aDraftWithoutSnapshotIsNotNumberedAndTheRetryIssuesItWithoutAGap() throws Exception {
        UUID store = fixtures.newStore();
        UUID series = fixtures.newSeries(store, "A", 1);
        UUID draft = fixtures.newDraft(store);

        assertThatThrownBy(() -> issue(store, draft)).isInstanceOf(FiscalIssueFailedException.class);

        assertThat(fixtures.nextNumber(series)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_error FROM fiscal.fiscal_document WHERE id = :id").param("id", draft)
                .query(String.class).single()).isEqualTo("PAYMENT_SNAPSHOT_MISSING");

        record(draft, PAID);
        assertThat(issue(store, draft).number()).isEqualTo(1L);
        assertThat(fixtures.nextNumber(series)).isEqualTo(2);
    }

    @Test
    void clearingTheOnAccountMarkClearsTheSnapshot() throws Exception {
        UUID draft = onAccountDraft();
        PaymentSnapshot onAccount = new PaymentSnapshot(PaymentStatus.ON_ACCOUNT, List.of(), mzn("116.00"),
                mzn("0.00"));
        record(draft, onAccount);

        DocumentView cleared = tx.execute(s -> fiscal.clearOnAccount(draft));

        assertThat(cleared.payments()).isNull();
        assertThat(fiscal.paymentSnapshot(draft)).isEmpty();
        assertThat(rows(draft)).isZero();
    }

    @Test
    void puttingADraftOnAccountClearsTheSnapshotTakenBefore() throws Exception {
        UUID store = fixtures.newStore();
        UUID order = UUID.randomUUID();
        UUID draft = tx.execute(s -> fiscal.createDraft(draft(store, SourceType.ORDER, order, order, null, null,
                false))).id();
        record(draft, PARTIAL);

        tx.execute(s -> fiscal.createDraft(draft(store, SourceType.ORDER, order, order,
                new Buyer("Cliente Fiel", null, null), UUID.randomUUID(), true)));

        assertThat(fiscal.paymentSnapshot(draft)).isEmpty();
    }

    private UUID onAccountDraft() throws Exception {
        UUID store = fixtures.newStore();
        UUID order = UUID.randomUUID();
        return tx.execute(s -> fiscal.createDraft(draft(store, SourceType.ORDER, order, order,
                new Buyer("Cliente Fiel", null, null), UUID.randomUUID(), true))).id();
    }

    private void record(UUID draft, PaymentSnapshot snapshot) {
        tx.executeWithoutResult(s -> fiscal.recordPayments(draft, snapshot));
    }

    private DocumentView issue(UUID store, UUID draft) {
        return asEmployee(store, employee, UUID.randomUUID(), () -> fiscal.issue(draft, employee));
    }

    private void assertMismatch(UUID draft, PaymentSnapshot snapshot) {
        assertThatThrownBy(() -> record(draft, snapshot)).isInstanceOf(PaymentSnapshotMismatchException.class)
                .extracting(e -> ((CodedException) e).code()).isEqualTo("PAYMENT_SNAPSHOT_MISMATCH");
    }

    private long rows(UUID draft) {
        return jdbc.sql("SELECT count(*) FROM fiscal.fiscal_document_payment WHERE document_id = :id")
                .param("id", draft).query(Long.class).single();
    }

    private static String snapshotSql(String status, String paid, String credit, String outstanding) {
        return "UPDATE fiscal.fiscal_document SET payment_status = '" + status + "', paid_total = " + paid
                + ", credit_total = " + credit + ", outstanding_total = " + outstanding + " WHERE id = :id";
    }

    /** Each statement runs and rolls back in its own transaction, so a refused one leaves the row as it was. */
    private void assertRefused(String sql, UUID id) {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.sql(sql).param("id", id).update();
        })).as(sql).isInstanceOf(DataAccessException.class);
    }
}
