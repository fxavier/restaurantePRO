package com.xavier.backend.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.audit.AuditEntry;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.ConflictException;
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

        ResponseEntity<?> first = idempotency.execute(scope, key, target, body, () -> {
            runs.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("version", 1, "status", "PUBLISHED"));
        });
        ResponseEntity<?> replay = idempotency.execute(scope, key, target, Map.of("note", "v1", "releaseId", "r-1"),
                () -> {
                    runs.incrementAndGet();
                    return ResponseEntity.ok("must not run");
                });

        assertThat(runs).hasValue(1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).hasToString("{\"status\":\"PUBLISHED\",\"version\":1}");
    }

    @Test
    void sameKeyWithADifferentBodyIsRejectedWith422() {
        idempotency.execute(scope, key, target, Map.of("price", "10.00"), () -> ResponseEntity.ok("done"));

        assertThatThrownBy(() -> idempotency.execute(scope, key, target, Map.of("price", "12.00"),
                () -> ResponseEntity.ok("must not run")))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void sameKeyAndBodyForAnotherTargetIsRejectedWith422() {
        AtomicInteger publishedB = new AtomicInteger();
        String releaseA = "POST /api/v1/backoffice/catalog/releases/A/publish";
        String releaseB = "POST /api/v1/backoffice/catalog/releases/B/publish";
        idempotency.execute(scope, key, releaseA, Map.of(), () -> ResponseEntity.ok("A published"));

        assertThatThrownBy(() -> idempotency.execute(scope, key, releaseB, Map.of(), () -> {
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
            Future<ResponseEntity<?>> first = executor.submit(
                    () -> idempotency.execute(scope, key, target, body, () -> {
                        runs.incrementAndGet();
                        firstIsRunning.countDown();
                        await(releaseFirst);
                        return ResponseEntity.ok(Map.of("paid", true));
                    }));
            await(firstIsRunning);

            assertThatThrownBy(() -> idempotency.execute(scope, key, target, body, () -> {
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
        idempotency.execute(scope, "probe-" + key, target, body, () -> ResponseEntity.ok("probe"));
        String hash = jdbc.sql("SELECT request_hash FROM shared.idempotency_record WHERE scope = :s AND key = :k")
                .param("s", scope).param("k", "probe-" + key).query(String.class).single();
        jdbc.sql("""
                        INSERT INTO shared.idempotency_record (scope, key, request_hash, status, created_at)
                        VALUES (:s, :k, :h, 'IN_PROGRESS', :t)
                        """)
                .param("s", scope).param("k", key).param("h", hash)
                .param("t", java.sql.Timestamp.from(Instant.now().minusSeconds(60))).update();
        AtomicInteger runs = new AtomicInteger();

        ResponseEntity<?> response = idempotency.execute(scope, key, target, body, () -> {
            runs.incrementAndGet();
            return ResponseEntity.ok("recovered");
        });
        ResponseEntity<?> replay = idempotency.execute(scope, key, target, body, () -> {
            runs.incrementAndGet();
            return ResponseEntity.ok("must not run");
        });

        assertThat(runs).hasValue(1);
        assertThat(response.getBody()).isEqualTo("recovered");
        assertThat(replay.getBody()).hasToString("\"recovered\"");
        // not a WARN and no crash claim: the same path runs when a concurrent duplicate locks first (harmless)
        assertThat(output.getOut().lines().filter(line -> line.contains("found an unfinished claim")))
                .singleElement().asString().contains("\"INFO\"").doesNotContain("crash");
    }

    @Test
    void aFailedActionLeavesTheKeyRetryable() {
        Map<String, Object> body = Map.of("amount", "5.00");
        assertThatThrownBy(() -> idempotency.execute(scope, key, target, body, () -> {
            throw new IllegalStateException("provider down");
        })).hasMessage("provider down");

        ResponseEntity<?> retry = idempotency.execute(scope, key, target, body, () -> ResponseEntity.ok("second try"));

        assertThat(retry.getBody()).isEqualTo("second try");
    }

    @Test
    void theActionsWritesAndTheStoredResponseCommitOrRollBackTogether() {
        String entityId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("amount", "5.00");

        // the action writes, then storing its response fails while serializing it
        assertThatThrownBy(() -> idempotency.execute(scope, key, target, body, () -> {
            auditLog.record(new AuditEntry(null, null, "PROBE_WRITTEN", "Probe", entityId, null, null, null, null));
            return ResponseEntity.ok(new UnserializableResponse("x"));
        })).hasMessageContaining("cannot be serialized");

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_id = :id")
                .param("id", entityId).query(Long.class).single()).isZero();
        assertThat(idempotency.execute(scope, key, target, body, () -> ResponseEntity.ok("clean retry")).getBody())
                .isEqualTo("clean retry");
    }

    @Test
    void theSameKeyInAnotherScopeIsIndependent() {
        idempotency.execute(scope, key, target, Map.of("a", 1), () -> ResponseEntity.ok("first scope"));

        ResponseEntity<?> other = idempotency.execute(scope + ":other-actor", key, target, Map.of("a", 2),
                () -> ResponseEntity.ok("other scope"));

        assertThat(other.getBody()).isEqualTo("other scope");
    }

    @Test
    void aMissingBlankOrOverlongKeyIsA400() {
        for (String badKey : new String[] {null, "", "   ", "k".repeat(IdempotencyService.MAX_KEY_LENGTH + 1)}) {
            assertThatThrownBy(() -> idempotency.execute(scope, badKey, target, Map.of(),
                    () -> ResponseEntity.ok("must not run")))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(idempotency.execute(scope, "k".repeat(IdempotencyService.MAX_KEY_LENGTH), target, Map.of(),
                () -> ResponseEntity.ok("longest key accepted")).getBody()).isEqualTo("longest key accepted");
    }

    @Test
    void callingItInsideATransactionFailsFastWithoutRunningTheAction() {
        // inside a caller's transaction the claim and the action could not commit on their own (D24)
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                idempotency.execute(scope, key, target, Map.of(), () -> {
                    runs.incrementAndGet();
                    return ResponseEntity.ok("must not run");
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside");

        assertThat(runs).hasValue(0);
        assertThat(jdbc.sql("SELECT count(*) FROM shared.idempotency_record WHERE scope = :s")
                .param("s", scope).query(Long.class).single()).isZero();
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
