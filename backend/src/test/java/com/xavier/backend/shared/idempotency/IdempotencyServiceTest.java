package com.xavier.backend.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.ConflictException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyServiceTest {

    @Autowired
    IdempotencyService idempotency;

    private final String scope = "catalog.publish:" + UUID.randomUUID();
    private final String key = UUID.randomUUID().toString();

    @Test
    void sameKeyAndBodyReplaysTheStoredResponseWithoutRunningAgain() {
        AtomicInteger runs = new AtomicInteger();
        Map<String, Object> body = Map.of("releaseId", "r-1", "note", "v1");

        ResponseEntity<?> first = idempotency.execute(scope, key, body, () -> {
            runs.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("version", 1, "status", "PUBLISHED"));
        });
        ResponseEntity<?> replay = idempotency.execute(scope, key, Map.of("note", "v1", "releaseId", "r-1"), () -> {
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
        idempotency.execute(scope, key, Map.of("price", "10.00"), () -> ResponseEntity.ok("done"));

        assertThatThrownBy(() -> idempotency.execute(scope, key, Map.of("price", "12.00"),
                () -> ResponseEntity.ok("must not run")))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void concurrentDuplicateGets409WhileTheFirstIsStillRunning() throws Exception {
        CountDownLatch firstIsRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Map<String, Object> body = Map.of("amount", "5.00");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ResponseEntity<?>> first = executor.submit(() -> idempotency.execute(scope, key, body, () -> {
                runs.incrementAndGet();
                firstIsRunning.countDown();
                await(releaseFirst);
                return ResponseEntity.ok(Map.of("paid", true));
            }));
            await(firstIsRunning);

            assertThatThrownBy(() -> idempotency.execute(scope, key, body, () -> {
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
    void aFailedActionReleasesTheKeySoTheClientCanRetry() {
        Map<String, Object> body = Map.of("amount", "5.00");
        assertThatThrownBy(() -> idempotency.execute(scope, key, body, () -> {
            throw new IllegalStateException("provider down");
        })).hasMessage("provider down");

        ResponseEntity<?> retry = idempotency.execute(scope, key, body, () -> ResponseEntity.ok("second try"));

        assertThat(retry.getBody()).isEqualTo("second try");
    }

    @Test
    void theSameKeyInAnotherScopeIsIndependent() {
        idempotency.execute(scope, key, Map.of("a", 1), () -> ResponseEntity.ok("first scope"));

        ResponseEntity<?> other = idempotency.execute(scope + ":other-actor", key, Map.of("a", 2),
                () -> ResponseEntity.ok("other scope"));

        assertThat(other.getBody()).isEqualTo("other scope");
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
