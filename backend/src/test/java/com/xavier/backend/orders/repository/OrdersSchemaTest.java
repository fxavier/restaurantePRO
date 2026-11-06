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

/** The order invariants the database enforces by itself (r2-decisions §4 orders, A15), written as raw SQL. */
@OrdersModuleTest
class OrdersSchemaTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void aConfirmedLineCannotBeUpdatedOrDeleted() {
        UUID order = order("COUNTER", null);
        UUID line = confirmedLine(order);

        assertThatThrownBy(() -> jdbc.sql("UPDATE orders.order_line SET quantity = 5 WHERE id = ?").param(line).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("CONFIRMED line is immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM orders.order_line WHERE id = ?").param(line).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("CONFIRMED line is immutable");
    }

    @Test
    void aDraftLineMayStillBeEditedAndDeleted() {
        UUID order = order("COUNTER", null);
        UUID line = draftLine(order);

        assertThat(jdbc.sql("UPDATE orders.order_line SET quantity = 5 WHERE id = ?").param(line).update()).isOne();
        assertThat(jdbc.sql("DELETE FROM orders.order_line WHERE id = ?").param(line).update()).isOne();
    }

    @Test
    void aConfirmedLineWithoutItsSnapshotOrOriginIsRefused() {
        UUID order = order("COUNTER", null);
        UUID line = draftLine(order);

        assertThatThrownBy(() -> jdbc.sql("UPDATE orders.order_line SET status = 'CONFIRMED' WHERE id = ?")
                .param(line).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDineInOrderNeedsATableAndOnlyADineInOrderHasOne() {
        assertThatThrownBy(() -> order("DINE_IN", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> order("COUNTER", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTableHasAtMostOneOpenOrder() {
        UUID table = UUID.randomUUID();
        UUID first = order("DINE_IN", table);

        assertThatThrownBy(() -> order("DINE_IN", table)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("order_header_open_table_uk");
        jdbc.sql("UPDATE orders.order_header SET lifecycle = 'CLOSED', closed_at = now(), "
                + "closed_by_employee_id = gen_random_uuid() WHERE id = ?").param(first).update();
        order("DINE_IN", table);
    }

    @Test
    void aRoundNumberIsUsedOncePerOrder() {
        UUID order = order("COUNTER", null);
        batch(order, 1);

        assertThatThrownBy(() -> batch(order, 1)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("order_batch_order_no_uk");
    }

    @Test
    void aLineCannotBelongToARoundOfAnotherOrder() {
        UUID batchOfOther = batch(order("COUNTER", null), 1);
        UUID order = order("COUNTER", null);

        assertThatThrownBy(() -> confirmedLine(order, batchOfOther))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aConfirmedLineNamesWhoConfirmedIt() {
        UUID order = order("COUNTER", null);
        UUID line = confirmedLine(order);
        UUID unattributed = draftLine(order);

        assertThatThrownBy(() -> jdbc.sql(CONFIRM.replace("confirmed_by_employee_id = gen_random_uuid()",
                        "confirmed_by_employee_id = NULL")).param(batch(order, 2)).param(unattributed).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(line).isNotNull();
    }

    @Test
    void aVoidOnlyTakesFromALineOfItsOwnOrder() {
        UUID line = confirmedLine(order("COUNTER", null));
        UUID other = order("COUNTER", null);

        assertThatThrownBy(() -> jdbc.sql(VOID).param(other).param(line).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTransferLineMovesFromALineOfTheSourceToALineOfTheTarget() {
        UUID source = order("COUNTER", null);
        UUID target = order("COUNTER", null);
        UUID stranger = order("COUNTER", null);
        UUID transfer = transfer(source, target);
        UUID sourceLine = confirmedLine(source);
        UUID strangerLine = confirmedLine(stranger);
        UUID targetLine = transferredLine(target, transfer);

        jdbc.sql(TRANSFER_LINE).param(transfer).param(source).param(target).param(sourceLine).param(targetLine)
                .update();
        assertThatThrownBy(() -> jdbc.sql(TRANSFER_LINE).param(transfer).param(source).param(target)
                .param(strangerLine).param(transferredLine(target, transfer)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(TRANSFER_LINE).param(transfer).param(stranger).param(target)
                .param(strangerLine).param(transferredLine(target, transfer)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    static final String VOID = """
            INSERT INTO orders.order_line_void (id, order_id, line_id, quantity, reason, wasted, operator_employee_id,
                approver_employee_id, terminal_id, voided_at)
            VALUES (gen_random_uuid(), ?, ?, 1, 'x', false, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
                now())""";

    static final String TRANSFER_LINE = """
            INSERT INTO orders.order_transfer_line (id, transfer_id, source_order_id, target_order_id, source_line_id,
                target_line_id, quantity)
            VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 1)""";

    UUID transfer(UUID source, UUID target) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders.order_transfer (id, store_id, source_order_id, target_order_id, employee_id,
                    terminal_id, transferred_at)
                VALUES (?, gen_random_uuid(), ?, ?, gen_random_uuid(), gen_random_uuid(), now())""")
                .param(id).param(source).param(target).update();
        return id;
    }

    /** A confirmed copy on the target, originating from the transfer (no batch). */
    UUID transferredLine(UUID target, UUID transfer) {
        UUID id = draftLine(target);
        jdbc.sql(CONFIRM.replace("batch_id = ?", "origin_transfer_id = ?")).param(transfer).param(id).update();
        return id;
    }

    UUID order(String mode, UUID table) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders.order_header (id, store_id, terminal_id, order_number, mode, table_id, lifecycle,
                    billing_lock, business_day, currency, opened_at, opened_by_employee_id, next_batch_no, version)
                VALUES (?, gen_random_uuid(), gen_random_uuid(), nextval('orders.order_number_seq'), ?, ?, 'OPEN',
                    'NONE', current_date, 'MZN', now(), gen_random_uuid(), 1, 0)""")
                .param(id).param(mode).param(table).update();
        return id;
    }

    UUID batch(UUID order, int no) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders.order_batch (id, order_id, store_id, batch_no, status, terminal_id, confirmed_at,
                    confirmed_by_employee_id, release_id, catalog_version)
                VALUES (?, ?, gen_random_uuid(), ?, 'CONFIRMED', gen_random_uuid(), now(), gen_random_uuid(),
                    gen_random_uuid(), 1)""").param(id).param(order).param(no).update();
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
        return confirmedLine(order, batch(order, 1));
    }

    static final String CONFIRM = """
            UPDATE orders.order_line SET status = 'CONFIRMED', item_code = 'X', description = 'X',
                unit_price = 1, currency = 'MZN', tax_category_id = gen_random_uuid(), tax_code = 'T',
                tax_rate = 16, release_id = gen_random_uuid(), catalog_version = 1, confirmed_at = now(),
                confirmed_by_employee_id = gen_random_uuid(), batch_id = ?
            WHERE id = ?""";

    UUID confirmedLine(UUID order, UUID batch) {
        UUID id = draftLine(order);
        jdbc.sql(CONFIRM).param(batch).param(id).update();
        return id;
    }
}
