package com.xavier.backend.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.dining.model.dto.CloseReason;
import com.xavier.backend.dining.model.dto.OccupationView;
import com.xavier.backend.dining.model.dto.TableState;
import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.CodedException;
import com.xavier.backend.shared.exception.ConflictException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code DiningApi} as orders (S13) calls it: inside the calling use case's transaction, with a POS session in the
 * security context. Writes are MANDATORY, so every call here runs in a {@link TransactionTemplate}.
 */
@DiningModuleTest
class DiningApiTest {

    @Autowired
    DiningApi dining;

    @Autowired
    DiningFixtures fixtures;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    final UUID employee = UUID.randomUUID();
    UUID store;
    UUID area;
    UUID table;

    @BeforeEach
    void aStoreWithATable() throws Exception {
        store = fixtures.newStore();
        area = fixtures.createArea(store, "SALA");
        table = fixtures.createTable(store, area, "M1");
    }

    @Test
    void openingAFreeTableOccupiesItForTheOrderAndIsAudited() {
        UUID order = UUID.randomUUID();

        OccupationView occupation = inPosTransaction(() -> dining.openOccupation(table, order, 3, employee));

        assertThat(occupation.tableId()).isEqualTo(table);
        assertThat(occupation.orderId()).isEqualTo(order);
        assertThat(occupation.storeId()).isEqualTo(store);
        assertThat(occupation.guests()).isEqualTo(3);
        assertThat(occupation.openedByEmployeeId()).isEqualTo(employee);
        assertThat(tableState().openOrderId()).isEqualTo(order);
        assertThat(fixtures.audits("TABLE_OCCUPATION_OPENED", occupation.id().toString(), store)).isEqualTo(1);
    }

    @Test
    void twoConcurrentOpeningsOfOneTableLetOneWinAndRefuseTheOtherAsTableOccupied() throws Exception {
        List<Integer> statuses = DiningFixtures.race(() -> {
            try {
                inPosTransaction(() -> dining.openOccupation(table, UUID.randomUUID(), 2, employee));
                return 200;
            } catch (ConflictException e) {
                assertThat(e.code()).isEqualTo("TABLE_OCCUPIED");
                return 409;
            }
        });

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void anInactiveTableIsRefusedAsTableInactive() throws Exception {
        fixtures.deactivateTable(store, table, area, "M1");

        assertRefused(() -> open(table, UUID.randomUUID()), 422, "TABLE_INACTIVE");
    }

    @Test
    void aTableInAnInactiveAreaIsRefusedAsTableInactive() throws Exception {
        fixtures.deactivateArea(store, area, "SALA");

        assertRefused(() -> open(table, UUID.randomUUID()), 422, "TABLE_INACTIVE");
    }

    @Test
    void aTableOfAnotherStoreThanTheSessionIsRefusedAsStoreMismatch() throws Exception {
        UUID otherStore = fixtures.newStore();
        UUID foreignTable = fixtures.createTable(otherStore, fixtures.createArea(otherStore, "SALA"), "M1");

        assertRefused(() -> open(foreignTable, UUID.randomUUID()), 422, "STORE_MISMATCH");
    }

    @Test
    void anOrderThatAlreadyOccupiesATableCannotOpenAnother() throws Exception {
        UUID order = UUID.randomUUID();
        UUID secondTable = fixtures.createTable(store, area, "M2");
        open(table, order);

        assertRefused(() -> open(secondTable, order), 409, "ORDER_SEATED");
        assertThat(inPosTransaction(() -> dining.tableState(secondTable)).openOrderId()).isNull();
    }

    @Test
    void anUnknownTableIsNotFound() {
        assertThatThrownBy(() -> open(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(com.xavier.backend.shared.exception.NotFoundException.class);
    }

    @Test
    void closingTheOccupationFreesTheTableForTheNextOrderAndIsAudited() {
        UUID order = UUID.randomUUID();
        OccupationView occupation = open(table, order);

        inPosTransaction(() -> run(() -> dining.closeOccupation(order, CloseReason.ORDER_CLOSED)));

        assertThat(tableState().openOrderId()).isNull();
        assertThat(fixtures.audits("TABLE_OCCUPATION_CLOSED", occupation.id().toString(), store)).isEqualTo(1);
        UUID next = UUID.randomUUID();
        open(table, next);
        assertThat(tableState().openOrderId()).isEqualTo(next);
    }

    @Test
    void closingAnOrderWithoutAnOpenOccupationIsAConflict() {
        UUID order = UUID.randomUUID();
        open(table, order);
        inPosTransaction(() -> run(() -> dining.closeOccupation(order, CloseReason.ORDER_CANCELLED)));

        assertRefused(() -> inPosTransaction(() -> run(() -> dining.closeOccupation(order, CloseReason.ORDER_CLOSED))),
                409, "NO_OPEN_OCCUPATION");
    }

    @Test
    void onlyAMoveClosesAnOccupationAsMoved() {
        UUID order = UUID.randomUUID();
        open(table, order);

        assertThatThrownBy(() -> inPosTransaction(() -> run(() -> dining.closeOccupation(order, CloseReason.MOVED))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tableState().openOrderId()).isEqualTo(order);
    }

    @Test
    void movingTheOrderFreesItsTableAndOccupiesTheTargetWithTheSameParty() throws Exception {
        UUID order = UUID.randomUUID();
        OccupationView before = open(table, order);
        UUID target = fixtures.createTable(store, area, "M2");

        OccupationView after = inPosTransaction(() -> dining.moveOccupation(order, target));

        assertThat(after.tableId()).isEqualTo(target);
        assertThat(after.id()).isNotEqualTo(before.id());
        assertThat(after.openedAt()).isEqualTo(before.openedAt());
        assertThat(after.guests()).isEqualTo(before.guests());
        assertThat(tableState().openOrderId()).isNull();
        assertThat(inPosTransaction(() -> dining.tableState(target)).openOrderId()).isEqualTo(order);
        assertThat(fixtures.audits("TABLE_OCCUPATION_MOVED", after.id().toString(), store)).isEqualTo(1);
    }

    @Test
    void anOrderCannotMoveToAnOccupiedTableOrToItsOwnTable() throws Exception {
        UUID order = UUID.randomUUID();
        open(table, order);
        UUID target = fixtures.createTable(store, area, "M2");
        open(target, UUID.randomUUID());

        assertRefused(() -> inPosTransaction(() -> dining.moveOccupation(order, target)), 409, "TABLE_OCCUPIED");
        assertRefused(() -> inPosTransaction(() -> dining.moveOccupation(order, table)), 409, "TABLE_OCCUPIED");
        assertThat(tableState().openOrderId()).isEqualTo(order);
    }

    @Test
    void anOrderCannotMoveToAnInactiveTableOrATableOfAnotherStore() throws Exception {
        UUID order = UUID.randomUUID();
        open(table, order);
        UUID inactive = fixtures.createTable(store, area, "M2");
        fixtures.deactivateTable(store, inactive, area, "M2");
        UUID otherStore = fixtures.newStore();
        UUID foreign = fixtures.createTable(otherStore, fixtures.createArea(otherStore, "SALA"), "M1");

        assertRefused(() -> inPosTransaction(() -> dining.moveOccupation(order, inactive)), 422, "TABLE_INACTIVE");
        assertRefused(() -> inPosTransaction(() -> dining.moveOccupation(order, foreign)), 422, "STORE_MISMATCH");
    }

    @Test
    void movingAnOrderWithoutAnOpenOccupationIsAConflict() throws Exception {
        UUID target = fixtures.createTable(store, area, "M2");

        assertRefused(() -> inPosTransaction(() -> dining.moveOccupation(UUID.randomUUID(), target)),
                409, "NO_OPEN_OCCUPATION");
    }

    @Test
    void printingTheSubtotalMarksTheOpenOccupationAndIsAudited() {
        UUID order = UUID.randomUUID();
        OccupationView occupation = open(table, order);

        inPosTransaction(() -> run(() -> dining.markSubtotalPrinted(order)));

        assertThat(fixtures.audits("TABLE_SUBTOTAL_PRINTED", occupation.id().toString(), store)).isEqualTo(1);
        assertRefused(() -> inPosTransaction(() -> run(() -> dining.markSubtotalPrinted(UUID.randomUUID()))),
                409, "NO_OPEN_OCCUPATION");
    }

    @Test
    void anOpeningSeesADeactivationCommittedAfterTheTableWasReadInTheSameTransaction() {
        assertRefused(() -> inPosTransaction(() -> {
            dining.tableState(table); // the table and area are now managed in this transaction
            committedElsewhere("UPDATE dining.restaurant_table SET active = false WHERE id = :id", table);
            return dining.openOccupation(table, UUID.randomUUID(), 2, employee);
        }), 422, "TABLE_INACTIVE");
    }

    @Test
    void anOpeningSeesAnAreaDeactivationCommittedAfterTheTableWasRead() {
        assertRefused(() -> inPosTransaction(() -> {
            dining.tableState(table);
            committedElsewhere("UPDATE dining.room_area SET active = false WHERE id = :id", area);
            return dining.openOccupation(table, UUID.randomUUID(), 2, employee);
        }), 422, "TABLE_INACTIVE");
    }

    @Test
    void anOpeningSeesALockCommittedAfterTheTableWasRead() {
        assertRefused(() -> inPosTransaction(() -> {
            dining.tableState(table);
            committedElsewhere(LOCK_SQL, table);
            return dining.openOccupation(table, UUID.randomUUID(), 2, employee);
        }), 422, "TABLE_LOCKED");
    }

    @Test
    void aMoveSeesALockOfItsTargetCommittedAfterTheTargetWasRead() throws Exception {
        UUID order = UUID.randomUUID();
        open(table, order);
        UUID target = fixtures.createTable(store, area, "M2");

        assertRefused(() -> inPosTransaction(() -> {
            dining.tableState(target);
            committedElsewhere(LOCK_SQL, target);
            return dining.moveOccupation(order, target);
        }), 422, "TABLE_LOCKED");
    }

    static final String LOCK_SQL = """
            UPDATE dining.restaurant_table
            SET locked_at = now(), locked_by_employee_id = gen_random_uuid(), lock_reason = 'x', version = version + 1
            WHERE id = :id""";

    /** Another session's committed write, while this thread's transaction is suspended. */
    void committedElsewhere(String sql, UUID id) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(status -> assertThat(jdbc.sql(sql).param("id", id).update()).isEqualTo(1));
    }

    @Test
    void writesRequireTheCallersTransaction() {
        assertThatThrownBy(() -> DiningActors.runAsPos(store, employee,
                () -> dining.openOccupation(table, UUID.randomUUID(), 2, employee)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    OccupationView open(UUID tableId, UUID order) {
        return inPosTransaction(() -> dining.openOccupation(tableId, order, 2, employee));
    }

    static void assertRefused(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CodedException.class, e -> {
            assertThat(e).isInstanceOf(status == 409 ? ConflictException.class : BusinessRuleException.class);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    static Void run(Runnable action) {
        action.run();
        return null;
    }

    TableState tableState() {
        return inPosTransaction(() -> dining.tableState(table));
    }

    <T> T inPosTransaction(Supplier<T> action) {
        return DiningActors.runAsPos(store, employee, () -> new TransactionTemplate(transactionManager)
                .execute(status -> action.get()));
    }
}
