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
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.shared.exception.BusinessRuleException;
import java.time.LocalDate;
import java.util.UUID;
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
