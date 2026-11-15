package com.xavier.backend.dining.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StrictStoreScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A74 for dining (D166): the no-show job runs in the {@code RESERVATIONS} system scope (all stores) on a thread with
 * neither an actor nor a fixture fallback, so it marks the due reservations of every store under row-level security.
 * The whole application, so the store-scope advisor and connection hook are those of production; seeded as the owner.
 * The job swallows and logs a failed run, so a missing scope shows as reservations left BOOKED.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@StrictStoreScope
class DiningActorlessScopeTest {

    @Autowired
    ReservationNoShowJob job;

    @Test
    void theNoShowJobMarksTheDueReservationsOfEveryStore() {
        List<UUID> due = List.of(dueReservation(UUID.randomUUID()), dueReservation(UUID.randomUUID()));

        job.markDue();

        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT status FROM dining.reservation WHERE id IN (?, ?)")
                .param(due.get(0)).param(due.get(1)).query(String.class).list()).containsExactly("NO_SHOW", "NO_SHOW");
    }

    private static UUID dueReservation(UUID store) {
        UUID id = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("""
                INSERT INTO dining.reservation (id, store_id, starts_at, party_size, contact_name, status,
                    created_by_actor_kind, created_at, updated_at, version)
                VALUES (?, ?, now() - interval '2 hours', 2, 'Ana', 'BOOKED', 'EMPLOYEE', now(), now(), 0)""")
                .param(id).param(store).update();
        return id;
    }
}
