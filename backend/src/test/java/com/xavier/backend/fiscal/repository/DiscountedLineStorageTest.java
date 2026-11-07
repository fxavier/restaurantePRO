package com.xavier.backend.fiscal.repository;

import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static com.xavier.backend.fiscal.FiscalFixtures.mzn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalModuleTest;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SourceType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S43 on PostgreSQL: a discounted line's list price and label are stored with its draft, come back in the issued
 * document's facts, are frozen with it (V10005), and the database itself refuses a list price below the unit price
 * or a label without a list price (V10011).
 */
@FiscalModuleTest
class DiscountedLineStorageTest {

    @Autowired
    FiscalApi fiscal;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void theIssuedDocumentsFactsCarryTheListPriceAndTheLabel() throws Exception {
        UUID document = issuedWithDiscount();

        List<DocumentView.Line> lines = fiscal.documentFacts(document).lines();

        assertThat(lines).extracting(DocumentView.Line::listUnitPrice, DocumentView.Line::discountLabel)
                .containsExactly(tuple(mzn("1.25"), "Happy hour"),
                        tuple(mzn("1.25"), null),
                        tuple(null, null));
        assertThat(lines).extracting(DocumentView.Line::gross)
                .containsExactly(mzn("2.26"), mzn("0.00"), mzn("58.00"));
    }

    @Test
    void theDiscountOfAnIssuedLineCannotChange() throws Exception {
        UUID document = issuedWithDiscount();

        assertRefused("UPDATE fiscal.fiscal_document_line SET list_unit_price = 2.00 WHERE document_id = :id",
                document);
        assertRefused("UPDATE fiscal.fiscal_document_line SET discount_label = 'x' WHERE document_id = :id", document);
        assertRefused("UPDATE fiscal.fiscal_document_line SET list_unit_price = NULL, discount_label = NULL "
                + "WHERE document_id = :id", document);
    }

    @Test
    void theDatabaseRefusesAListPriceBelowTheUnitPriceOrALabelWithoutOne() throws Exception {
        UUID draft = fixtures.newDraft(fixtures.newStore()); // one line at 58.00

        assertThatThrownBy(() -> jdbc.sql("UPDATE fiscal.fiscal_document_line SET list_unit_price = 57.99 "
                + "WHERE document_id = :id").param("id", draft).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fiscal_document_line_list_price_ck");
        assertThatThrownBy(() -> jdbc.sql("UPDATE fiscal.fiscal_document_line SET discount_label = 'Happy hour' "
                + "WHERE document_id = :id").param("id", draft).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fiscal_document_line_discount_label_ck");
    }

    /** 2 x Café at 1.13 (10 % off 1.25), 1 x Café offered, 1 x Cerveja 2M at 58.00 (no discount); 16 %. */
    private UUID issuedWithDiscount() throws Exception {
        UUID store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
        UUID order = UUID.randomUUID();
        List<DraftLine> lines = List.of(
                line("Café", "2", "1.13", "2.26", "1.95", "0.31", "1.25", "Happy hour"),
                line("Café", "1", "0.00", "0.00", "0.00", "0.00", "1.25", null),
                line("Cerveja 2M", "1", "58.00", "58.00", "50.00", "8.00", null, null));
        DraftCommand command = new DraftCommand(store, SourceType.ORDER, order, order, LocalDate.of(2026, 11, 4),
                null, null, false, lines, mzn("60.26"), mzn("51.95"), mzn("8.31"));
        UUID draft = tx.execute(s -> fiscal.createDraft(command)).id();
        fixtures.recordPaid(draft);
        UUID employee = UUID.randomUUID();
        return asEmployee(store, employee, UUID.randomUUID(), () -> fiscal.issue(draft, employee)).id();
    }

    private static DraftLine line(String description, String quantity, String unitPrice, String gross, String net,
                                  String tax, String list, String label) {
        return new DraftLine(UUID.randomUUID(), description, new BigDecimal(quantity), mzn(unitPrice),
                new BigDecimal("16"), mzn(gross), mzn(net), mzn(tax), "IVA", list == null ? null : mzn(list), label);
    }

    private void assertRefused(String sql, UUID id) {
        assertThatThrownBy(() -> jdbc.sql(sql).param("id", id).update()).as(sql)
                .isInstanceOf(DataAccessException.class);
    }
}
