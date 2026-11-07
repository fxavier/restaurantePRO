package com.xavier.backend.orders.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.orders.OrdersModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The R4 pricing invariants the database enforces by itself (V6009–V6012, D101, D105, D108), written as raw SQL. */
@OrdersModuleTest
class OrdersPricingSchemaTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void anEffectivePriceAboveTheListPriceIsRefused() {
        UUID order = order();
        UUID line = draftLine(order);

        assertThatThrownBy(() -> jdbc.sql(CONFIRM.replace("unit_price = 1", "unit_price = 2, list_unit_price = 1"))
                .param(batch(order)).param(line).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("order_line_effective_within_list_ck");
    }

    @Test
    void anR3LineHasNoListPriceAndIsAStandardLine() {
        UUID order = order();
        UUID line = confirmedLine(order);

        assertThat(jdbc.sql("SELECT kind, list_unit_price IS NULL, automatic FROM orders.order_line WHERE id = ?")
                .param(line).query((rs, n) -> rs.getString(1) + rs.getBoolean(2) + rs.getBoolean(3)).single())
                .isEqualTo("STANDARDtruefalse");
    }

    @Test
    void aComponentOfAConfirmedLineIsImmutable() {
        UUID order = order();
        UUID line = draftLine(order);
        UUID component = component(order, line);
        jdbc.sql(CONFIRM).param(batch(order)).param(line).update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE orders.order_line_component SET quantity = 2 WHERE id = ?")
                .param(component).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("components are immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM orders.order_line_component WHERE id = ?").param(component)
                .update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("components are immutable");
        assertThatThrownBy(() -> component(order, line))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("components are immutable");
    }

    @Test
    void aComponentOfADraftMayChange() {
        UUID order = order();
        UUID line = draftLine(order);
        UUID component = component(order, line);

        assertThat(jdbc.sql("UPDATE orders.order_line_component SET item_code = 'S' WHERE id = ?").param(component)
                .update()).isOne();
        assertThat(jdbc.sql("DELETE FROM orders.order_line_component WHERE id = ?").param(component).update())
                .isOne();
    }

    @Test
    void anAdjustmentIsAnImmutableFactThatLowersThePrice() {
        UUID order = order();
        UUID line = confirmedLine(order);
        UUID adjustment = adjustment(order, line, 1, "DISCOUNT", "1.0000", "0.9000", "gen_random_uuid()");

        assertThatThrownBy(() -> jdbc.sql("UPDATE orders.order_line_price_adjustment SET reason = 'y' WHERE id = ?")
                .param(adjustment).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("a fact is immutable");
        assertThatThrownBy(() -> adjustment(order, line, 2, "DISCOUNT", "0.9000", "0.9500", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("order_line_price_adjustment_direction_ck");
        assertThatThrownBy(() -> adjustment(order, line, 1, "DISCOUNT", "0.9000", "0.8000", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("order_line_price_adjustment_line_seq_uk");
    }

    @Test
    void anOfferIsPriceZeroWithAnApprover() {
        UUID order = order();
        UUID line = confirmedLine(order);

        assertThatThrownBy(() -> adjustment(order, line, 1, "OFFER", "1.0000", "0.0000", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("order_line_price_adjustment_offer_ck");
        assertThatThrownBy(() -> adjustment(order, line, 1, "OFFER", "1.0000", "0.5000", "gen_random_uuid()"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("order_line_price_adjustment_offer_ck");
        adjustment(order, line, 1, "OFFER", "1.0000", "0.0000", "gen_random_uuid()");
    }

    @Test
    void aSplitLineHasExactlyOneOriginAndTheSplitIsAFact() {
        UUID order = order();
        UUID source = confirmedLine(order);
        UUID split = UUID.randomUUID();
        UUID adjustment = UUID.randomUUID();
        UUID target = draftLine(order);

        inTransaction(() -> {
            jdbc.sql(CONFIRM.replace("batch_id = ?", "origin_split_id = ?")).param(split).param(target).update();
            jdbc.sql("""
                    INSERT INTO orders.order_line_split (id, order_id, source_line_id, target_line_id, quantity,
                        adjustment_id, at, employee_id)
                    VALUES (?, ?, ?, ?, 1, ?, now(), gen_random_uuid())""")
                    .param(split).param(order).param(source).param(target).param(adjustment).update();
            jdbc.sql(ADJUSTMENT.formatted("gen_random_uuid()").replaceFirst("gen_random_uuid\\(\\)", "?"))
                    .param(adjustment).param(order).param(target).param(1).param("DISCOUNT").param("1.0000")
                    .param("0.9000").update();
        });

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM orders.order_line_split WHERE id = ?").param(split).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("a fact is immutable");
        UUID twoOrigins = draftLine(order);
        assertThatThrownBy(() -> jdbc.sql(CONFIRM.replace("batch_id = ?", "batch_id = ?, origin_split_id = ?"))
                .param(batch(order)).param(split).param(twoOrigins).update())
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("order_line_one_origin_ck");
    }

    @Test
    void anOrderMayNameItsCustomer() {
        UUID order = order();
        UUID customer = UUID.randomUUID();

        jdbc.sql("UPDATE orders.order_header SET customer_id = ? WHERE id = ?").param(customer).param(order).update();

        assertThat(jdbc.sql("SELECT customer_id FROM orders.order_header WHERE id = ?").param(order)
                .query(UUID.class).single()).isEqualTo(customer);
    }

    static final String CONFIRM = """
            UPDATE orders.order_line SET status = 'CONFIRMED', item_code = 'X', description = 'X',
                unit_price = 1, currency = 'MZN', tax_category_id = gen_random_uuid(), tax_code = 'T',
                tax_rate = 16, release_id = gen_random_uuid(), catalog_version = 1, confirmed_at = now(),
                confirmed_by_employee_id = gen_random_uuid(), batch_id = ?
            WHERE id = ?""";

    static final String ADJUSTMENT = """
            INSERT INTO orders.order_line_price_adjustment (id, order_id, line_id, seq, kind, quantity,
                previous_unit_price, unit_price, currency, reason, operator_employee_id, approver_employee_id, at)
            VALUES (gen_random_uuid(), ?, ?, ?, ?, 1, ?::numeric, ?::numeric, 'MZN', 'x', gen_random_uuid(), %s,
                now())""";

    UUID adjustment(UUID order, UUID line, int seq, String kind, String previous, String price, String approver) {
        jdbc.sql(ADJUSTMENT.formatted(approver)).param(order).param(line).param(seq).param(kind).param(previous)
                .param(price).update();
        return jdbc.sql("SELECT id FROM orders.order_line_price_adjustment WHERE line_id = ? AND seq = ?")
                .param(line).param(seq).query(UUID.class).single();
    }

    UUID component(UUID order, UUID line) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders.order_line_component (id, order_id, line_id, group_code, item_id, quantity, position)
                VALUES (?, ?, ?, 'STARTER', gen_random_uuid(), 1,
                    (SELECT count(*) FROM orders.order_line_component WHERE line_id = ?))""")
                .param(id).param(order).param(line).param(line).update();
        return id;
    }

    UUID order() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders.order_header (id, store_id, terminal_id, order_number, mode, lifecycle,
                    billing_lock, business_day, currency, opened_at, opened_by_employee_id, next_batch_no, version)
                VALUES (?, gen_random_uuid(), gen_random_uuid(), nextval('orders.order_number_seq'), 'COUNTER',
                    'OPEN', 'NONE', current_date, 'MZN', now(), gen_random_uuid(), 1, 0)""").param(id).update();
        return id;
    }

    UUID batch(UUID order) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders.order_batch (id, order_id, store_id, batch_no, status, terminal_id, confirmed_at,
                    confirmed_by_employee_id, release_id, catalog_version)
                VALUES (?, ?, gen_random_uuid(),
                    (SELECT count(*) + 1 FROM orders.order_batch WHERE order_id = ?), 'CONFIRMED',
                    gen_random_uuid(), now(), gen_random_uuid(), gen_random_uuid(), 1)""")
                .param(id).param(order).param(order).update();
        return id;
    }

    UUID draftLine(UUID order) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders.order_line (id, order_id, status, item_id, quantity, created_at,
                    created_by_employee_id, version)
                VALUES (?, ?, 'DRAFT', gen_random_uuid(), 1, now(), gen_random_uuid(), 0)""")
                .param(id).param(order).update();
        return id;
    }

    UUID confirmedLine(UUID order) {
        UUID id = draftLine(order);
        jdbc.sql(CONFIRM).param(batch(order)).param(id).update();
        return id;
    }

    void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }
}
