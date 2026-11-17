package com.xavier.backend.dining.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.orders.BootstrapSeed;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StrictStoreScope;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
@SpringBootTest(properties = "restaurantpro.jobs.batch-per-tenant=3")
@Import(TestcontainersConfiguration.class)
@StrictStoreScope
@StrictTenantScope
class DiningActorlessScopeTest {

    @Autowired
    ReservationNoShowJob job;

    static final UUID T1 = UUID.fromString("0190b400-0000-7000-8000-0000000000c7");
    static final UUID T2 = UUID.fromString("0190b400-0000-7000-8000-0000000000c8");
    static final UUID SUSPENDED = UUID.fromString("0190b400-0000-7000-8000-0000000000c9");

    @BeforeEach
    void activeTenants() {
        TenantTestSupport.ensureTenant(T1, "dining-job-1", "ACTIVE");
        TenantTestSupport.ensureTenant(T2, "dining-job-2", "ACTIVE");
        TenantTestSupport.ensureTenant(SUSPENDED, "dining-job-3", "SUSPENDED");
    }

    /** Back to no ACTIVE test tenant: the other suites' jobs iterate the bootstrap tenant only. */
    @AfterEach
    void closeTenants() {
        TenantTestSupport.ensureTenant(T1, "dining-job-1", "CLOSED");
        TenantTestSupport.ensureTenant(T2, "dining-job-2", "CLOSED");
        TenantTestSupport.ensureTenant(SUSPENDED, "dining-job-3", "CLOSED");
    }

    /** R6 (D185): the job runs once per ACTIVE tenant, each time inside that tenant, and skips a suspended one. */
    @Test
    void theNoShowJobRunsInEveryActiveTenantAndSkipsASuspendedOne() {
        UUID first = dueReservationOf(T1, 1).getFirst();
        UUID second = dueReservationOf(T2, 1).getFirst();
        UUID suspended = dueReservationOf(SUSPENDED, 1).getFirst();

        job.markDue();

        assertThat(statusOf(first)).isEqualTo("NO_SHOW");
        assertThat(statusOf(second)).isEqualTo("NO_SHOW");
        assertThat(statusOf(suspended)).as("a suspended tenant is skipped").isEqualTo("BOOKED");
    }

    /** R6: at most restaurantpro.jobs.batch-per-tenant (3 here) reservations of a tenant per run; the next run goes on. */
    @Test
    void aRunMarksAtMostTheBatchPerTenantAndTheNextRunTakesTheRest() {
        List<UUID> due = dueReservationOf(T1, 5);

        job.markDue();

        assertThat(due.stream().filter(id -> statusOf(id).equals("NO_SHOW")).count()).isEqualTo(3);

        job.markDue();

        assertThat(due).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("NO_SHOW"));
    }

    @Test
    void theNoShowJobMarksTheDueReservationsOfEveryStore() {
        List<UUID> due = List.of(dueReservation(UUID.randomUUID()), dueReservation(UUID.randomUUID()));

        job.markDue();

        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT status FROM dining.reservation WHERE id IN (?, ?)")
                .param(due.get(0)).param(due.get(1)).query(String.class).list()).containsExactly("NO_SHOW", "NO_SHOW");
    }

    private static String statusOf(UUID reservation) {
        return OwnerDatabase.shared().jdbc().sql("SELECT status FROM dining.reservation WHERE id = ?")
                .param(reservation).query(String.class).single();
    }

    /** {@code count} due reservations in {@code tenant}, seeded as its application role, as a store would. */
    private static List<UUID> dueReservationOf(UUID tenant, int count) {
        List<UUID> ids = new java.util.ArrayList<>();
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            UUID store = UUID.randomUUID();
            for (int i = 0; i < count; i++) {
                UUID id = UUID.randomUUID();
                app.jdbc().sql("""
                        INSERT INTO dining.reservation (id, store_id, starts_at, party_size, contact_name, status,
                            created_by_actor_kind, created_at, updated_at, version)
                        VALUES (?, ?, now() - interval '2 hours', 2, 'Ana', 'BOOKED', 'EMPLOYEE', now(), now(), 0)""")
                        .param(id).param(store).update();
                ids.add(id);
            }
        }
        return ids;
    }

    private static UUID dueReservation(UUID store) {
        UUID id = UUID.randomUUID();
        BootstrapSeed.jdbc().sql("""
                INSERT INTO dining.reservation (id, store_id, starts_at, party_size, contact_name, status,
                    created_by_actor_kind, created_at, updated_at, version)
                VALUES (?, ?, now() - interval '2 hours', 2, 'Ana', 'BOOKED', 'EMPLOYEE', now(), now(), 0)""")
                .param(id).param(store).update();
        return id;
    }
}
