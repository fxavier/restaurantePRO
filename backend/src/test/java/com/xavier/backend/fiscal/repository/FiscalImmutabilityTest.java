package com.xavier.backend.fiscal.repository;

import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalModuleTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirmed facts are never edited (spec §8, A15): the database itself refuses to change an ISSUED document, its
 * lines or its tax summary, and to skip or reset a series number. Plain SQL, as a bug or a manual fix would do it.
 */
@FiscalModuleTest
class FiscalImmutabilityTest {

    @Autowired
    FiscalApi fiscal;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void anIssuedDocumentCannotBeUpdatedOrDeleted() throws Exception {
        UUID document = issued();

        assertRefused("UPDATE fiscal.fiscal_document SET buyer_name = 'Outro' WHERE id = :id", document);
        assertRefused("UPDATE fiscal.fiscal_document SET status = 'DRAFT', number = NULL, series_id = NULL, "
                + "issued_at = NULL WHERE id = :id", document);
        assertRefused("DELETE FROM fiscal.fiscal_document WHERE id = :id", document);
    }

    @Test
    void theLinesAndTaxesOfAnIssuedDocumentCannotChange() throws Exception {
        UUID document = issued();

        assertRefused("UPDATE fiscal.fiscal_document_line SET description = 'x' WHERE document_id = :id", document);
        assertRefused("DELETE FROM fiscal.fiscal_document_line WHERE document_id = :id", document);
        assertRefused("""
                INSERT INTO fiscal.fiscal_document_line
                    (id, document_id, line_no, description, quantity, unit_price, tax_rate, gross, net, tax)
                VALUES (gen_random_uuid(), :id, 99, 'extra', 1, 1, 0, 1, 1, 0)""", document);
        assertRefused("UPDATE fiscal.fiscal_document_tax SET tax = 0 WHERE document_id = :id", document);
        assertRefused("DELETE FROM fiscal.fiscal_document_tax WHERE document_id = :id", document);
        assertRefused("""
                INSERT INTO fiscal.fiscal_document_tax (id, document_id, tax_rate, net, tax)
                VALUES (gen_random_uuid(), :id, 99, 0, 0)""", document);
    }

    @Test
    void aDraftNeverHoldsANumber() throws Exception {
        UUID store = fixtures.newStore();
        UUID series = fixtures.newSeries(store, "A", 1);
        UUID draft = fixtures.newDraft(store);

        assertThatThrownBy(() -> jdbc.sql("UPDATE fiscal.fiscal_document SET number = 1, series_id = :series "
                        + "WHERE id = :id").param("series", series).param("id", draft).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.sql("UPDATE fiscal.fiscal_document SET last_error = 'x' WHERE id = :id")
                .param("id", draft).update()).isEqualTo(1);
    }

    @Test
    void aSeriesNumberCanOnlyAdvanceByOneAndASeriesIsNeverDeleted() throws Exception {
        UUID series = fixtures.newSeries(fixtures.newStore(), "A", 10);

        assertRefused("UPDATE fiscal.fiscal_series SET next_number = next_number + 2 WHERE id = :id", series);
        assertRefused("UPDATE fiscal.fiscal_series SET next_number = 10, first_number = 5 WHERE id = :id", series);
        assertRefused("UPDATE fiscal.fiscal_series SET code = 'Z' WHERE id = :id", series);
        assertRefused("DELETE FROM fiscal.fiscal_series WHERE id = :id", series);
        assertThat(fixtures.nextNumber(series)).isEqualTo(10);
    }

    @Test
    void aReprintRecordIsAppendOnly() throws Exception {
        UUID document = issued();
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_document_print
                    (id, document_id, copy_no, requested_at, requested_by_employee_id, terminal_id)
                VALUES (gen_random_uuid(), :id, 1, now(), gen_random_uuid(), gen_random_uuid())""")
                .param("id", document).update();

        assertRefused("UPDATE fiscal.fiscal_document_print SET copy_no = 7 WHERE document_id = :id", document);
        assertRefused("DELETE FROM fiscal.fiscal_document_print WHERE document_id = :id", document);
    }

    /** TRUNCATE skips row triggers. Each attempt runs in a rolled-back transaction, so the shared data is safe. */
    @Test
    void theDocumentTablesCannotBeTruncated() {
        for (String table : List.of("fiscal_document", "fiscal_document_line", "fiscal_document_tax",
                "fiscal_document_print")) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                status.setRollbackOnly();
                jdbc.sql("TRUNCATE fiscal." + table + " CASCADE").update();
            })).as(table).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("TRUNCATE");
        }
    }

    private UUID issued() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        UUID draft = fixtures.newDraft(store);
        UUID employee = UUID.randomUUID();
        return asEmployee(store, employee, UUID.randomUUID(), () -> fiscal.issue(draft, employee)).id();
    }

    private void assertRefused(String sql, UUID id) {
        assertThatThrownBy(() -> jdbc.sql(sql).param("id", id).update())
                .as(sql)
                .isInstanceOf(DataAccessException.class);
    }
}
