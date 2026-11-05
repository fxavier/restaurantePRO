package com.xavier.backend.fiscal.service;

import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static com.xavier.backend.fiscal.FiscalFixtures.draft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalModuleTest;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.ConflictException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** FiscalApi.createDraft (D66, D70): ORDER or PARTITION drafts, on account, with the order's business day. */
@FiscalModuleTest
class PartitionDraftTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 4);

    @Autowired
    FiscalApi fiscal;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aDraftNeedsTheCallersTransaction() {
        assertThatThrownBy(() -> fiscal.createDraft(draft(UUID.randomUUID(), SourceType.PARTITION,
                UUID.randomUUID(), UUID.randomUUID(), null, null, false)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aPartitionDraftBelongsToItsOrderAndKeepsTheBusinessDay() throws Exception {
        UUID store = fixtures.newStore();
        UUID partition = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        DocumentView document = create(draft(store, SourceType.PARTITION, partition, order, null, null, false));

        assertThat(document.status()).isEqualTo("DRAFT");
        assertThat(document.sourceType()).isEqualTo("PARTITION");
        assertThat(document.sourceId()).isEqualTo(partition);
        assertThat(document.orderId()).isEqualTo(order);
        assertThat(document.businessDate()).isEqualTo(DAY);
        assertThat(document.onAccount()).isFalse();
        assertThat(orderRefOf(document.id())).isEqualTo(order);
    }

    @Test
    void anOrderDraftWithoutAnExplicitOrderBelongsToItsSource() throws Exception {
        UUID store = fixtures.newStore();
        UUID order = UUID.randomUUID();

        DocumentView document = create(draft(store, SourceType.ORDER, order, null, null, null, false));

        assertThat(document.orderId()).isEqualTo(order);
        assertThat(orderRefOf(document.id())).isEqualTo(order);
    }

    @Test
    void aPartitionDraftNeedsItsOrder() throws Exception {
        UUID store = fixtures.newStore();

        assertThatThrownBy(() -> create(draft(store, SourceType.PARTITION, UUID.randomUUID(), null, null, null,
                false))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("orderId");
    }

    @Test
    void anOrderDraftCannotBelongToAnotherOrder() throws Exception {
        UUID store = fixtures.newStore();

        assertThatThrownBy(() -> create(draft(store, SourceType.ORDER, UUID.randomUUID(), UUID.randomUUID(), null,
                null, false))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("orderId");
    }

    @Test
    void anOnAccountDraftNeedsItsCustomer() throws Exception {
        UUID store = fixtures.newStore();

        assertThatThrownBy(() -> create(draft(store, SourceType.ORDER, UUID.randomUUID(), null, null, null, true)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("customer");
    }

    @Test
    void theSameSourceAgainUpdatesTheDraftsBuyerCustomerAndOnAccount() throws Exception {
        UUID store = fixtures.newStore();
        UUID partition = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        Buyer buyer = new Buyer("Cliente Lda", "500999888", null);
        DocumentView first = create(draft(store, SourceType.PARTITION, partition, order, null, null, false));

        DocumentView again = create(draft(store, SourceType.PARTITION, partition, order, buyer, customer, true));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.buyer()).isEqualTo(buyer);
        assertThat(again.customerId()).isEqualTo(customer);
        assertThat(again.onAccount()).isTrue();
        assertThat(again.version()).isGreaterThan(first.version());
        assertThat(documentsOfSource(partition)).isEqualTo(1);
    }

    @Test
    void aNullBuyerKeepsTheDraftsBuyerAndAnIdenticalCommandChangesNothing() throws Exception {
        UUID store = fixtures.newStore();
        UUID partition = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        Buyer buyer = new Buyer("Cliente Lda", "500999888", null);
        DocumentView first = create(draft(store, SourceType.PARTITION, partition, order, buyer, null, false));

        DocumentView again = create(draft(store, SourceType.PARTITION, partition, order, null, null, false));

        assertThat(again.buyer()).isEqualTo(buyer);
        assertThat(again.version()).isEqualTo(first.version());
    }

    @Test
    void anotherBuyerReplacesTheDraftsBuyer() throws Exception {
        UUID store = fixtures.newStore();
        UUID partition = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        create(draft(store, SourceType.PARTITION, partition, order, new Buyer("Antigo", null, null), null, false));

        DocumentView again = create(draft(store, SourceType.PARTITION, partition, order,
                new Buyer("Novo", "400111222", null), null, false));

        assertThat(again.buyer()).isEqualTo(new Buyer("Novo", "400111222", null));
    }

    @Test
    void onAccountIsStickyAndKeepsItsCustomer() throws Exception {
        UUID store = fixtures.newStore();
        UUID partition = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        create(draft(store, SourceType.PARTITION, partition, order, null, customer, true));

        assertMismatch(() -> create(draft(store, SourceType.PARTITION, partition, order, null, null, false)));
        assertMismatch(() -> create(draft(store, SourceType.PARTITION, partition, order, null, customer, false)));
        assertMismatch(() -> create(draft(store, SourceType.PARTITION, partition, order, null, UUID.randomUUID(),
                true)));
        DocumentView same = create(draft(store, SourceType.PARTITION, partition, order, null, customer, true));
        assertThat(same.onAccount()).isTrue();
        assertThat(same.customerId()).isEqualTo(customer);
    }

    @Test
    void theSameSourceWithOtherLinesOrTotalsIsRefused() throws Exception {
        UUID store = fixtures.newStore();
        UUID partition = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        DraftCommand original = draft(store, SourceType.PARTITION, partition, order, null, null, false);
        create(original);
        DraftLine line = original.lines().getFirst();
        DraftLine otherSourceLine = new DraftLine(UUID.randomUUID(), line.description(), line.quantity(),
                line.unitPrice(), line.taxRatePercent(), line.gross(), line.net(), line.tax());
        DraftLine sameScaledDifferently = new DraftLine(line.sourceLineId(), line.description(),
                new BigDecimal("2.000"), line.unitPrice(), line.taxRatePercent(), line.gross(), line.net(),
                line.tax());

        assertMismatch(() -> create(withLines(original, List.of(otherSourceLine))));
        assertMismatch(() -> create(withLines(original, List.of(line, line))));
        DocumentView same = create(withLines(original, List.of(sameScaledDifferently)));
        assertThat(same.lines()).hasSize(1);
        assertThat(documentsOfSource(partition)).isEqualTo(1);
    }

    @Test
    void anIssuedDocumentIsReturnedUnchanged() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "P", 1);
        UUID partition = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID draftId = create(draft(store, SourceType.PARTITION, partition, order, null, null, false)).id();
        UUID employee = UUID.randomUUID();
        DocumentView issued = asEmployee(store, employee, UUID.randomUUID(), () -> fiscal.issue(draftId, employee));

        DocumentView again = create(draft(store, SourceType.PARTITION, partition, order,
                new Buyer("Outro", null, null), UUID.randomUUID(), true));

        assertThat(again).isEqualTo(issued);
    }

    @Test
    void theDatabaseRefusesAPartitionWithoutOrderAndOnAccountWithoutCustomer() throws Exception {
        UUID store = fixtures.newStore();
        UUID draftId = create(draft(store, SourceType.PARTITION, UUID.randomUUID(), UUID.randomUUID(), null, null,
                false)).id();

        assertThatThrownBy(() -> jdbc.sql("UPDATE fiscal.fiscal_document SET order_id = NULL WHERE id = :id")
                .param("id", draftId).update()).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fiscal_document_partition_order_ck");
        assertThatThrownBy(() -> jdbc.sql("UPDATE fiscal.fiscal_document SET on_account = true WHERE id = :id")
                .param("id", draftId).update()).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fiscal_document_on_account_customer_ck");
    }

    private static DraftCommand withLines(DraftCommand c, List<DraftLine> lines) {
        Money gross = lines.stream().map(DraftLine::gross).reduce(Money::plus).orElseThrow();
        Money net = lines.stream().map(DraftLine::net).reduce(Money::plus).orElseThrow();
        Money tax = lines.stream().map(DraftLine::tax).reduce(Money::plus).orElseThrow();
        return new DraftCommand(c.storeId(), c.sourceType(), c.sourceId(), c.orderId(), c.businessDate(), c.buyer(),
                c.customerId(), c.onAccount(), lines, gross, net, tax);
    }

    private static void assertMismatch(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("DRAFT_MISMATCH"));
    }

    private DocumentView create(DraftCommand command) {
        return tx.execute(s -> fiscal.createDraft(command));
    }

    private UUID orderRefOf(UUID document) {
        return jdbc.sql("SELECT order_ref FROM fiscal.fiscal_document WHERE id = :id").param("id", document)
                .query(UUID.class).single();
    }

    private long documentsOfSource(UUID source) {
        return jdbc.sql("SELECT count(*) FROM fiscal.fiscal_document WHERE source_id = :source")
                .param("source", source).query(Long.class).single();
    }
}
