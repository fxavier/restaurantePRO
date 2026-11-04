package com.xavier.backend.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.ConflictException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class IdempotencyServiceTest {

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    IdempotencyRetention retention;

    @Autowired
    AuditLog auditLog;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final String scope = "catalog.release.publish:" + UUID.randomUUID();
    private final String key = UUID.randomUUID().toString();
    private final String target = "POST /api/v1/backoffice/catalog/releases/" + UUID.randomUUID() + "/publish";

    @Test
    void sameKeyAndBodyReplaysTheStoredResponseWithoutRunningAgain() {
        AtomicInteger runs = new AtomicInteger();
        Map<String, Object> body = Map.of("releaseId", "r-1", "note", "v1");

        ResponseEntity<Published> first = idempotency.execute(scope, key, target, body, Published.class, () -> {
            runs.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(new Published("PUBLISHED", 1));
        });
        ResponseEntity<Published> replay = idempotency.execute(scope, key, target,
                Map.of("note", "v1", "releaseId", "r-1"), Published.class, () -> {
                    runs.incrementAndGet();
                    return ResponseEntity.ok(new Published("must not run", 0));
                });

        assertThat(runs).hasValue(1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(new Published("PUBLISHED", 1)); // typed, not a JSON tree (D47.1)
    }

    @Test
    void sameKeyWithADifferentBodyIsRejectedWith422() {
        idempotency.execute(scope, key, target, Map.of("price", "10.00"), String.class,
                () -> ResponseEntity.ok("done"));

        assertThatThrownBy(() -> idempotency.execute(scope, key, target, Map.of("price", "12.00"),
                String.class, () -> ResponseEntity.ok("must not run")))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void sameKeyAndBodyForAnotherTargetIsRejectedWith422() {
        AtomicInteger publishedB = new AtomicInteger();
        String releaseA = "POST /api/v1/backoffice/catalog/releases/A/publish";
        String releaseB = "POST /api/v1/backoffice/catalog/releases/B/publish";
        idempotency.execute(scope, key, releaseA, Map.of(), String.class, () -> ResponseEntity.ok("A published"));

        assertThatThrownBy(() -> idempotency.execute(scope, key, releaseB, Map.of(), String.class, () -> {
            publishedB.incrementAndGet();
            return ResponseEntity.ok("B published");
        })).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(publishedB).hasValue(0);
    }

    @Test
    void concurrentDuplicateGets409WhileTheFirstIsStillRunning() throws Exception {
        CountDownLatch firstIsRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Map<String, Object> body = Map.of("amount", "5.00");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ResponseEntity<String>> first = executor.submit(
                    () -> idempotency.execute(scope, key, target, body, String.class, () -> {
                        runs.incrementAndGet();
                        firstIsRunning.countDown();
                        await(releaseFirst);
                        return ResponseEntity.ok("paid");
                    }));
            await(firstIsRunning);

            assertThatThrownBy(() -> idempotency.execute(scope, key, target, body, String.class, () -> {
                runs.incrementAndGet();
                return ResponseEntity.ok("must not run");
            }))
                    .isInstanceOf(IdempotentRequestInProgressException.class)
                    .isInstanceOf(ConflictException.class);

            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(runs).hasValue(1);
    }

    @Test
    void aClaimLeftBehindByACrashedRequestIsTakenOverAndRunsOnce(CapturedOutput output) {
        Map<String, Object> body = Map.of("amount", "5.00");
        // first request claimed the key, then the process died: its action transaction rolled back
        idempotency.execute(scope, "probe-" + key, target, body, String.class, () -> ResponseEntity.ok("probe"));
        String hash = jdbc.sql("SELECT request_hash FROM shared.idempotency_record WHERE scope = :s AND key = :k")
                .param("s", scope).param("k", "probe-" + key).query(String.class).single();
        jdbc.sql("""
                        INSERT INTO shared.idempotency_record (scope, key, request_hash, status, created_at)
                        VALUES (:s, :k, :h, 'IN_PROGRESS', :t)
                        """)
                .param("s", scope).param("k", key).param("h", hash)
                .param("t", java.sql.Timestamp.from(Instant.now().minusSeconds(60))).update();
        AtomicInteger runs = new AtomicInteger();

        ResponseEntity<String> response = idempotency.execute(scope, key, target, body, String.class, () -> {
            runs.incrementAndGet();
            return ResponseEntity.ok("recovered");
        });
        ResponseEntity<String> replay = idempotency.execute(scope, key, target, body, String.class, () -> {
            runs.incrementAndGet();
            return ResponseEntity.ok("must not run");
        });

        assertThat(runs).hasValue(1);
        assertThat(response.getBody()).isEqualTo("recovered");
        assertThat(replay.getBody()).isEqualTo("recovered"); // a String comes back as itself (D47.1)
        // not a WARN and no crash claim: the same path runs when a concurrent duplicate locks first (harmless)
        assertThat(output.getOut().lines().filter(line -> line.contains("found an unfinished claim")))
                .singleElement().asString().contains("\"INFO\"").doesNotContain("crash");
    }

    @Test
    void aFailedActionLeavesTheKeyRetryable() {
        Map<String, Object> body = Map.of("amount", "5.00");
        assertThatThrownBy(() -> idempotency.execute(scope, key, target, body, String.class, () -> {
            throw new IllegalStateException("provider down");
        })).hasMessage("provider down");

        ResponseEntity<String> retry = idempotency.execute(scope, key, target, body, String.class,
                () -> ResponseEntity.ok("second try"));

        assertThat(retry.getBody()).isEqualTo("second try");
    }

    @Test
    void theActionsWritesAndTheStoredResponseCommitOrRollBackTogether() {
        String entityId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("amount", "5.00");

        // the action writes, then storing its response fails while serializing it
        assertThatThrownBy(() -> idempotency.execute(scope, key, target, body, UnserializableResponse.class, () -> {
            auditLog.recordAsSystem("PROBE_WRITTEN", "Probe", entityId, null, null, null, null);
            return ResponseEntity.ok(new UnserializableResponse("x"));
        })).hasMessageContaining("cannot be serialized");

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_id = :id")
                .param("id", entityId).query(Long.class).single()).isZero();
        assertThat(idempotency.execute(scope, key, target, body, String.class,
                () -> ResponseEntity.ok("clean retry")).getBody())
                .isEqualTo("clean retry");
    }

    @Test
    void theSameKeyInAnotherScopeIsIndependent() {
        idempotency.execute(scope, key, target, Map.of("a", 1), String.class, () -> ResponseEntity.ok("first scope"));

        ResponseEntity<String> other = idempotency.execute(scope + ":other-actor", key, target, Map.of("a", 2),
                String.class, () -> ResponseEntity.ok("other scope"));

        assertThat(other.getBody()).isEqualTo("other scope");
    }

    @Test
    void aMissingBlankOrOverlongKeyIsA400() {
        for (String badKey : new String[] {null, "", "   ", "k".repeat(IdempotencyService.MAX_KEY_LENGTH + 1)}) {
            assertThatThrownBy(() -> idempotency.execute(scope, badKey, target, Map.of(),
                    String.class, () -> ResponseEntity.ok("must not run")))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(idempotency.execute(scope, "k".repeat(IdempotencyService.MAX_KEY_LENGTH), target, Map.of(),
                String.class, () -> ResponseEntity.ok("longest key accepted")).getBody()).isEqualTo("longest key accepted");
    }

    @Test
    void callingItInsideATransactionFailsFastWithoutRunningTheAction() {
        // inside a caller's transaction the claim and the action could not commit on their own (D24)
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                idempotency.execute(scope, key, target, Map.of(), String.class, () -> {
                    runs.incrementAndGet();
                    return ResponseEntity.ok("must not run");
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside");

        assertThat(runs).hasValue(0);
        assertThat(jdbc.sql("SELECT count(*) FROM shared.idempotency_record WHERE scope = :s")
                .param("s", scope).query(Long.class).single()).isZero();
    }

    @Test
    void aReplayRestoresTheLocationHeader() {
        URI location = URI.create("/api/v1/pos/payment-attempts/" + UUID.randomUUID());
        Map<String, Object> body = Map.of("amount", "5.00");
        ResponseEntity<Published> first = idempotency.execute(scope, key, target, body, Published.class,
                () -> ResponseEntity.accepted().location(location).body(new Published("CREATED", 0)));

        ResponseEntity<Published> replay = idempotency.execute(scope, key, target, body, Published.class,
                () -> ResponseEntity.ok(new Published("must not run", 0)));

        assertThat(first.getHeaders().getLocation()).isEqualTo(location);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getHeaders().getLocation()).isEqualTo(location);
        assertThat(replay.getBody()).isEqualTo(new Published("CREATED", 0));
    }

    @Test
    void aBusinessRuleErrorIsNotStoredSoARetryReEvaluatesTheRule() {
        Map<String, Object> body = Map.of("amount", "500.00");
        assertThatThrownBy(() -> idempotency.execute(scope, key, target, body, String.class, () -> {
            throw new BalanceExceeded();
        })).isInstanceOf(BalanceExceeded.class);

        ResponseEntity<String> retry = idempotency.execute(scope, key, target, body, String.class,
                () -> ResponseEntity.ok("paid after a top-up"));

        assertThat(retry.getBody()).isEqualTo("paid after a top-up");
        assertThat(storedStatus(key)).isEqualTo("COMPLETED");
    }

    @Test
    void aNon2xxResponseIsAProgrammingErrorAndIsNotStored() {
        String entityId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("amount", "5.00");

        assertThatThrownBy(() -> idempotency.execute(scope, key, target, body, String.class, () -> {
            auditLog.recordAsSystem("PROBE_WRITTEN", "Probe", entityId, null, null, null, null);
            return ResponseEntity.unprocessableContent().body("balance exceeded");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("2xx");

        assertThat(storedStatus(key)).isEqualTo("IN_PROGRESS"); // the claim only: a retry runs the action again
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_id = :id")
                .param("id", entityId).query(Long.class).single()).isZero();
    }

    @Test
    void retentionDeletesCompletedRecordsAndAbandonedClaimsOlderThanTheRetentionPeriod() {
        Instant now = Instant.now();
        insert("old-completed", "COMPLETED", now.minus(Duration.ofDays(120)), now.minus(Duration.ofDays(91)));
        insert("recent-completed", "COMPLETED", now.minus(Duration.ofDays(120)), now.minus(Duration.ofDays(89)));
        // a claim left behind by a crash or a refused (422) action: nobody retries it after the retention period
        insert("old-in-progress", "IN_PROGRESS", now.minus(Duration.ofDays(91)), null);
        insert("recent-in-progress", "IN_PROGRESS", now.minus(Duration.ofDays(89)), null);

        int deleted = retention.purgeExpired();

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("SELECT key FROM shared.idempotency_record WHERE scope = :s")
                .param("s", scope).query(String.class).list())
                .containsExactlyInAnyOrder("recent-completed", "recent-in-progress");
    }

    private void insert(String recordKey, String status, Instant createdAt, Instant completedAt) {
        boolean completed = completedAt != null;
        jdbc.sql("""
                        INSERT INTO shared.idempotency_record
                            (scope, key, request_hash, status, response_status, response_body, created_at, completed_at)
                        VALUES (:s, :k, :h, :status, :rs, CAST(:body AS jsonb), :created, :completed)
                        """)
                .param("s", scope).param("k", recordKey).param("h", "0".repeat(64)).param("status", status)
                .param("rs", completed ? 200 : null).param("body", completed ? "\"ok\"" : null)
                .param("created", Timestamp.from(createdAt))
                .param("completed", completed ? Timestamp.from(completedAt) : null)
                .update();
    }

    private String storedStatus(String recordKey) {
        return jdbc.sql("SELECT status FROM shared.idempotency_record WHERE scope = :s AND key = :k")
                .param("s", scope).param("k", recordKey).query(String.class).single();
    }

    record Published(String status, int version) {
    }

    static final class BalanceExceeded extends BusinessRuleException {
        BalanceExceeded() {
            super("The payment exceeds the balance.");
        }
    }

    record UnserializableResponse(String value) {
        @Override
        public String value() {
            throw new IllegalStateException("this response cannot be serialized");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
