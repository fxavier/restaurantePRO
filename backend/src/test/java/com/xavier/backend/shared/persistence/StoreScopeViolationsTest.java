package com.xavier.backend.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.shared.persistence.StoreScopeViolations.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PermissionDeniedDataAccessException;

/** D170: a refusal by row-level security is recognised by its SQL state, counted by kind and remembered 24 h. */
class StoreScopeViolationsTest {

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-14T10:00:00Z"));
    final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final StoreScopeViolations violations = new StoreScopeViolations(registry, clock);

    @Test
    void theThreeRefusalsAreClassifiedByTheirSqlStateAnywhereInTheCauseChain() {
        assertThat(StoreScopeViolations.classify(new DataIntegrityViolationException("x",
                new SQLException("store scope not set (origin none)", "RS001")))).contains(Kind.SCOPE_MISSING);
        assertThat(StoreScopeViolations.classify(new PermissionDeniedDataAccessException("x",
                new SQLException("new row violates row-level security policy for table \"t\"", "42501"))))
                .contains(Kind.WRITE_REFUSED);
        assertThat(StoreScopeViolations.classify(new RuntimeException(new RuntimeException(
                new SQLException("store change refused", "RS002"))))).contains(Kind.STORE_CHANGE);
    }

    @Test
    void otherErrorsAreNotViolations() {
        assertThat(StoreScopeViolations.classify(new SQLException("permission denied for table t", "42501")))
                .as("a missing grant is not a row-level security refusal").isEmpty();
        assertThat(StoreScopeViolations.classify(new SQLException("duplicate key", "23505"))).isEmpty();
        assertThat(StoreScopeViolations.classify(new IllegalStateException("no sql"))).isEmpty();
    }

    @Test
    void aRecordedViolationIsCountedByKind() {
        violations.record(Kind.WRITE_REFUSED, "listener:X.on");
        violations.record(Kind.WRITE_REFUSED, "listener:X.on");

        assertThat(registry.get("store_scope_violations").tag("kind", "write_refused").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("store_scope_violations").tag("kind", "scope_missing").counter().count())
                .as("every kind is published, at 0 when none happened").isZero();
    }

    @Test
    void theLastDayIsRememberedByKindAndForgottenAfter24Hours() {
        violations.record(Kind.SCOPE_MISSING, "system:X");
        now.set(now.get().plus(Duration.ofHours(1)));
        violations.record(Kind.SCOPE_MISSING, "system:X");
        violations.record(Kind.STORE_CHANGE, "account:1");

        assertThat(violations.lastDay()).hasSize(2);
        assertThat(violations.lastDay().get(Kind.SCOPE_MISSING).count()).isEqualTo(2);
        assertThat(violations.lastDay().get(Kind.SCOPE_MISSING).first())
                .isEqualTo(Instant.parse("2026-11-14T10:00:00Z"));

        now.set(now.get().plus(Duration.ofHours(23)).plusSeconds(1));

        assertThat(violations.lastDay().get(Kind.SCOPE_MISSING).count()).isEqualTo(1);
        now.set(now.get().plus(Duration.ofHours(2)));
        assertThat(violations.lastDay()).isEmpty();
    }
}
