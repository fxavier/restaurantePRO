package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.DrawerKicks;
import com.xavier.backend.production.DrawerKicks.Till;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.shared.events.EventPublicationRecovery;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D158 (amended 2026-11-13, S60 review): the 30 s window runs from the payment's or refund's recording
 * ({@code requestedAt}), not from when production got the event. An outstanding publication delivered hours later
 * (a crashed listener, a restart, a person's resubmission) leaves a FAILED {@code KICK_EXPIRED} job and sends
 * nothing; so does a publication stored before {@code requestedAt} existed. (A fresh delivery still kicks:
 * {@code DrawerKickOncePerPaymentTest}.)
 */
@ProductionModuleTest
class DrawerKickLateRequestTest {

    /** The serialized {@code requestedAt} member, whatever Jackson made of the instant. */
    static final String REQUESTED_AT = "\"requestedAt\":(\"[^\"]*\"|[0-9.eE+-]+|null)";

    @Autowired
    DrawerKicks kicks;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EventPublicationRecovery recovery;

    @Test
    void aPaymentRecordedTwoHoursAgoNeverKicksWhenItsEventComesLate() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);
            String twoHoursAgo = Instant.now().minus(Duration.ofHours(2)).toString();

            UUID job = outstanding(till, "\"requestedAt\":\"" + twoHoursAgo + "\"");

            assertThat(fixtures.job(job)).containsEntry("status", "FAILED")
                    .containsEntry("last_error", "KICK_EXPIRED").containsEntry("attempts", 0);
            assertThat(fake.received()).hasSize(1).as("only the template's kick");
        }
    }

    @Test
    void aPublicationStoredBeforeRequestedAtExistedNeverKicks() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);

            UUID job = outstanding(till, null);

            assertThat(fixtures.job(job)).containsEntry("status", "FAILED")
                    .containsEntry("last_error", "KICK_EXPIRED");
            assertThat(fake.received()).hasSize(1).as("only the template's kick");
        }
    }

    /**
     * A kick publication from 2 h ago left outstanding (FAILED), copied from a delivered one on the till with its
     * {@code requestedAt} member replaced by {@code requestedAt} ({@code null}: removed), then resubmitted and
     * settled by the worker; the late kick's job.
     */
    private UUID outstanding(Till till, String requestedAt) throws Exception {
        UUID template = UUID.randomUUID();
        kicks.requested(till, "PAYMENT", template);
        await(() -> kicks.handled(template) == 1, "template handled");
        UUID templateJob = kicks.job(template);
        fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(templateJob)), "template kick sent");
        UUID late = UUID.randomUUID();
        int copied = jdbc.sql("""
                        INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,
                            completion_date, status, completion_attempts)
                        SELECT gen_random_uuid(), listener_id, event_type,
                            regexp_replace(replace(serialized_event, :template, :late), :pattern, :member),
                            now() - interval '2 hours', NULL, 'FAILED', 1
                        FROM event_publication
                        WHERE serialized_event LIKE :like AND listener_id LIKE '%DrawerKickListener%'""")
                .param("template", template.toString()).param("late", late.toString())
                .param("pattern", requestedAt == null ? "," + REQUESTED_AT : REQUESTED_AT)
                .param("member", requestedAt == null ? "" : requestedAt)
                .param("like", "%" + template + "%").update();
        assertThat(copied).isEqualTo(1);

        recovery.resubmitOlderThan(Duration.ofHours(1));
        await(() -> kicks.handled(late) == 1, "late delivery handled");
        UUID job = kicks.job(late);
        fixtures.runWorkerUntil(() -> {
            String status = fixtures.jobStatus(job);
            return !"QUEUED".equals(status) && !"PROCESSING".equals(status);
        }, "late kick settled");
        return job;
    }
}
