package com.xavier.backend.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.dining.model.dto.OccupationView;
import com.xavier.backend.dining.model.dto.TableState;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;

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
            } catch (ErrorResponseException e) {
                assertThat(e.getBody().getProperties()).containsEntry("code", "TABLE_OCCUPIED");
                return e.getStatusCode().value();
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
    void writesRequireTheCallersTransaction() {
        assertThatThrownBy(() -> DiningActors.runAsPos(store, employee,
                () -> dining.openOccupation(table, UUID.randomUUID(), 2, employee)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    OccupationView open(UUID tableId, UUID order) {
        return inPosTransaction(() -> dining.openOccupation(tableId, order, 2, employee));
    }

    static void assertRefused(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ErrorResponseException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(status);
            assertThat(e.getBody().getProperties()).containsEntry("code", code);
        });
    }

    TableState tableState() {
        return inPosTransaction(() -> dining.tableState(table));
    }

    <T> T inPosTransaction(Supplier<T> action) {
        return DiningActors.runAsPos(store, employee, () -> new TransactionTemplate(transactionManager)
                .execute(status -> action.get()));
    }
}
