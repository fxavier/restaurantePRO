package com.xavier.backend.dining.service;

import static com.xavier.backend.dining.DiningActors.inStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.dining.DiningModuleTest;
import com.xavier.backend.dining.repository.ReservationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P47: a BOOKED reservation becomes NO_SHOW once {@code starts_at + grace} has passed, as SYSTEM, audited, skipping
 * rows a request holds ({@code SKIP LOCKED}, D46), and a second run changes nothing. The job runs with a fixed
 * {@code Clock} days ahead of the real one, so the scheduled instance of the context never reaches these rows.
 */
@DiningModuleTest
class ReservationNoShowJobTest {

    @Autowired
    ReservationRepository reservations;

    @Autowired
    DiningChanges changes;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DiningFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

    @Autowired
    StoreScopes scopes;

    final Instant startsAt = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.MINUTES);
    UUID store;
    UUID reservation;

    @BeforeEach
    void aReservationInThreeDays() throws Exception {
        store = fixtures.newStore();
        reservation = fixtures.book(store, startsAt);
    }

    ReservationNoShowJob jobAt(Duration afterStart) {
        return new ReservationNoShowJob(reservations, changes, transactionManager,
                Clock.fixed(startsAt.plus(afterStart), ZoneOffset.UTC));
    }

    @Test
    void aReservationWithinItsGraceStaysBooked() {
        jobAt(Duration.ofMinutes(29)).markDue();

        assertThat(fixtures.reservationStatus(reservation)).isEqualTo("BOOKED");
    }

    @Test
    void aReservationPastItsGraceBecomesANoShowAsTheSystemOnce() {
        jobAt(Duration.ofMinutes(30)).markDue();
        jobAt(Duration.ofMinutes(45)).markDue();

        assertThat(fixtures.reservationStatus(reservation)).isEqualTo("NO_SHOW");
        assertThat(jdbc.sql("SELECT no_show_by_actor_kind FROM dining.reservation WHERE id = :id")
                .param("id", reservation).query(String.class).single()).isEqualTo("SYSTEM");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event
                        WHERE action = 'RESERVATION_NO_SHOW' AND entity_id = :id AND actor_kind = 'SYSTEM'""")
                .param("id", reservation.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theStoreGraceSettingDecidesWhenTheNoShowIsDue() throws Exception {
        mvc.perform(put("/api/v1/backoffice/dining/settings/" + store)
                        .with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reservationWindowMinutes": 120, "noShowGraceMinutes": 60}"""))
                .andExpect(status().isOk());

        jobAt(Duration.ofMinutes(45)).markDue();
        assertThat(fixtures.reservationStatus(reservation)).isEqualTo("BOOKED");

        jobAt(Duration.ofMinutes(60)).markDue();
        assertThat(fixtures.reservationStatus(reservation)).isEqualTo("NO_SHOW");
    }

    @Test
    void aReservationARequestHoldsIsSkippedWithoutWaitingAndTakenNextRun() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            // a request of the reservation's store, on a thread of its own (no fixture fallback there)
            Future<?> holder = pool.submit(() -> scopes.callForStore(store, "test:holder",
                    () -> tx.execute(status -> {
                        jdbc.sql("SELECT id FROM dining.reservation WHERE id = :id FOR UPDATE")
                                .param("id", reservation).query(UUID.class).single();
                        locked.countDown();
                        await(release);
                        return null;
                    })));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            jobAt(Duration.ofMinutes(31)).markDue(); // would block here without SKIP LOCKED

            assertThat(fixtures.reservationStatus(reservation)).isEqualTo("BOOKED");
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }

        jobAt(Duration.ofMinutes(32)).markDue();
        assertThat(fixtures.reservationStatus(reservation)).isEqualTo("NO_SHOW");
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
