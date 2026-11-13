package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.DrawerKicks;
import com.xavier.backend.production.DrawerKicks.Till;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D158 (amended 2026-11-13, S60 review, S63): the kick's 30 s window is anchored on
 * {@code min(requestedAt, now)}, so a settlement clock ahead of production's (skew) cannot lengthen it; and a kick
 * whose window has already closed when production issues it is saved FAILED {@code KICK_EXPIRED} at once, so the
 * recorded cause is the lateness whatever the printer's mode at claim time.
 */
@ProductionModuleTest
class DrawerKickRequestedAtTest {

    @Autowired
    DrawerKicks kicks;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Test
    void aRequestStampedInTheFutureIsAnchoredOnTheIssueTime() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);
            fixtures.mode(till.store(), till.printer(), "HOLD");
            UUID payment = UUID.randomUUID();

            kicks.requested(till, "PAYMENT", payment, Instant.now().plus(Duration.ofMinutes(10)));
            await(() -> kicks.handled(payment) == 1, "kick handled");
            Instant handled = Instant.now();

            Instant createdAt = ((Timestamp) fixtures.job(kicks.job(payment)).get("created_at")).toInstant();
            assertThat(createdAt).as("the window starts no later than the issue").isBeforeOrEqualTo(handled);
        }
    }

    @Test
    void aKickAlreadyExpiredWhenIssuedIsFailedKickExpiredEvenOnAHeldPrinter() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);
            fixtures.mode(till.store(), till.printer(), "HOLD");
            UUID payment = UUID.randomUUID();

            kicks.requested(till, "PAYMENT", payment, Instant.now().minus(Duration.ofMinutes(2)));
            await(() -> kicks.handled(payment) == 1, "kick handled");

            assertThat(fixtures.job(kicks.job(payment))).containsEntry("status", "FAILED")
                    .containsEntry("last_error", "KICK_EXPIRED").containsEntry("attempts", 0);
            assertThat(fake.received()).isEmpty();
        }
    }
}
