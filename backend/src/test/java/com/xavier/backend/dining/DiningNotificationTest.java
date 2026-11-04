package com.xavier.backend.dining;

import static com.xavier.backend.dining.DiningActors.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Every change that the POS table map shows notifies {@code TABLES} for its store after commit (D60). */
@DiningModuleTest
class DiningNotificationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DiningFixtures fixtures;

    @Autowired
    DiningApi dining;

    @Autowired
    PlatformTransactionManager transactionManager;

    UUID store;
    UUID area;
    UUID table;
    DiningChangeStream stream;

    @BeforeEach
    void aStoreWithATableAndAStream() throws Exception {
        store = fixtures.newStore();
        area = fixtures.createArea(store, "SALA");
        table = fixtures.createTable(store, area, "M1");
        stream = DiningChangeStream.subscribe(mvc, store);
    }

    @Test
    void openingAndClosingAnOccupationNotifyTheTable() throws Exception {
        UUID order = fixtures.seat(store, table);
        stream.awaitTables(table);
        int afterOpen = stream.received().length();

        DiningActors.runAsPos(store, UUID.randomUUID(), () -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    dining.closeOccupation(order, com.xavier.backend.dining.model.dto.CloseReason.ORDER_CLOSED);
                    return null;
                }));

        eventually(() -> stream.received().length() > afterOpen && stream.received().substring(afterOpen)
                .contains(table.toString()));
    }

    @Test
    void aRolledBackOpeningNotifiesNothing() throws Exception {
        UUID other = fixtures.createTable(store, area, "M2");
        stream.awaitTables(other); // its creation
        DiningActors.runAsPos(store, UUID.randomUUID(), () -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    dining.openOccupation(other, UUID.randomUUID(), 2, UUID.randomUUID());
                    status.setRollbackOnly();
                    return null;
                }));

        fixtures.seat(store, table);

        stream.awaitTables(table);
        assertThat(occurrences(other)).isEqualTo(1);
    }

    @Test
    void lockingATableNotifiesIt() throws Exception {
        mvc.perform(post(DiningFixtures.POS_TABLES + "/" + table + "/lock").with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(pos(store, UUID.randomUUID(), Permission.TABLE_LOCK))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"x\"}")).andExpect(status().isOk());

        stream.awaitTables(table);
    }

    @Test
    void creatingAndChangingTablesAndAreasInTheBackOfficeNotifiesThem() throws Exception {
        UUID created = fixtures.createTable(store, area, "M2");
        stream.awaitTables(created);

        fixtures.deactivateTable(store, created, area, "M2");
        UUID terrace = fixtures.createArea(store, "ESPL");
        stream.awaitTables(terrace);
        fixtures.deactivateArea(store, terrace, "ESPL");

        eventually(() -> occurrences(terrace) == 2 && occurrences(created) == 2);
    }

    int occurrences(UUID id) {
        return stream.received().split(id.toString(), -1).length - 1;
    }

    static void eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in 5 s");
            }
            Thread.sleep(20);
        }
    }
}
