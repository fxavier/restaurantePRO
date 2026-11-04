package com.xavier.backend.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.TestcontainersConfiguration;
import java.io.IOException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.ResourceHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D63: {@link IdempotencyService#executeSteps} claims the key with a lease, runs its steps with no transaction and
 * no record lock, and stores the response only when its owner completes. Real PostgreSQL (spec §12). The
 * single-connection proof is {@link IdempotencyStepsSingleConnectionTest} (its own context; a top-level class, because
 * Surefire's per-class text report counts a JUnit {@code @Nested} class's tests under the wrong name).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyStepsTest {

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    IdempotencyRetention retention;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final String scope = "settlement.order.close:" + UUID.randomUUID();
    private final String key = UUID.randomUUID().toString();
    private final String target = "POST /api/v1/pos/orders/" + UUID.randomUUID() + "/close";
    private final Map<String, Object> body = Map.of("orderVersion", 3);

    @Test
    void theStepsRunOnceAndTheSameKeyReplaysTheStoredResponse() {
        AtomicInteger runs = new AtomicInteger();
        URI location = URI.create("/api/v1/pos/orders/" + UUID.randomUUID() + "/documents/" + UUID.randomUUID());

        ResponseEntity<Closed> first = idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
            runs.incrementAndGet();
            return ResponseEntity.created(location).body(new Closed("CLOSED", 4));
        });
        ResponseEntity<Closed> replay = idempotency.executeSteps(scope, key, target, Map.of("orderVersion", 3),
                Closed.class, () -> {
                    runs.incrementAndGet();
                    return ResponseEntity.ok(new Closed("must not run", 0));
                });

        assertThat(runs).hasValue(1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getLocation()).isEqualTo(location);
        assertThat(replay.getBody()).isEqualTo(new Closed("CLOSED", 4));
    }

    @Test
    void aDuplicateWhileTheLeaseIsLiveGets409AndDoesNotRunTheSteps() throws Exception {
        CountDownLatch firstIsRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ResponseEntity<Closed>> first = executor.submit(() ->
                    idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
                        runs.incrementAndGet();
                        firstIsRunning.countDown();
                        await(releaseFirst);
                        return ResponseEntity.ok(new Closed("CLOSED", 4));
                    }));
            await(firstIsRunning);

            assertThatThrownBy(() -> idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
                runs.incrementAndGet();
                return ResponseEntity.ok(new Closed("must not run", 0));
            })).isInstanceOf(IdempotentRequestInProgressException.class);

            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).getBody()).isEqualTo(new Closed("CLOSED", 4));
        }
        assertThat(runs).hasValue(1);
        // the lease is restaurantpro.idempotency.step-lease (PT30S) from the claim
        assertThat(jdbc.sql("""
                        SELECT EXTRACT(EPOCH FROM lease_until - created_at) FROM shared.idempotency_record
                        WHERE scope = :s AND key = :k""")
                .param("s", scope).param("k", key).query(Double.class).single()).isEqualTo(30.0);
    }

    @Test
    void anAbandonedClaimWithAnExpiredLeaseIsTakenOverByExactlyOneOfConcurrentRetries() throws Exception {
        // the first owner claimed the key and crashed: its lease has expired, nothing was stored
        insertAbandonedClaim(Instant.now().minusSeconds(5));
        int retries = 6;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger effects = new AtomicInteger();
        List<Future<ResponseEntity<Closed>>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(retries)) {
            for (int i = 0; i < retries; i++) {
                results.add(executor.submit(() -> {
                    await(start);
                    return idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
                        effects.incrementAndGet();
                        sleep(Duration.ofMillis(300)); // the others arrive while the taker runs
                        return ResponseEntity.ok(new Closed("CLOSED", 4));
                    });
                }));
            }
            start.countDown();
            int succeeded = 0;
            int inProgress = 0;
            for (Future<ResponseEntity<Closed>> result : results) {
                try {
                    assertThat(result.get(10, TimeUnit.SECONDS).getBody()).isEqualTo(new Closed("CLOSED", 4));
                    succeeded++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(IdempotentRequestInProgressException.class);
                    inProgress++;
                }
            }
            assertThat(succeeded).isPositive();
            assertThat(succeeded + inProgress).isEqualTo(retries);
        }
        assertThat(effects).hasValue(1);
        assertThat(storedStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void anOwnerThatLostItsLeaseReplaysTheResponseStoredByTheTaker() throws Exception {
        CountDownLatch slowOwnerIsRunning = new CountDownLatch(1);
        CountDownLatch releaseSlowOwner = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ResponseEntity<Closed>> slowOwner = executor.submit(() ->
                    idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
                        slowOwnerIsRunning.countDown();
                        await(releaseSlowOwner);
                        return ResponseEntity.ok(new Closed("slow owner", 99));
                    }));
            await(slowOwnerIsRunning);
            expireLease(); // the slow owner outlived its lease

            ResponseEntity<Closed> taker = idempotency.executeSteps(scope, key, target, body, Closed.class,
                    () -> ResponseEntity.ok(new Closed("CLOSED", 4)));
            releaseSlowOwner.countDown();

            assertThat(taker.getBody()).isEqualTo(new Closed("CLOSED", 4));
            assertThat(slowOwner.get(10, TimeUnit.SECONDS).getBody()).isEqualTo(new Closed("CLOSED", 4));
        }
        assertThat(idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> ResponseEntity.ok(new Closed("must not run", 0))).getBody()).isEqualTo(new Closed("CLOSED", 4));
    }

    @Test
    void anOwnerThatLostItsLeaseAndThenFailsDoesNotReleaseTheTakersLease() throws Exception {
        Blocked loser = new Blocked();
        Blocked taker = new Blocked();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<Closed>> a = executor.submit(() -> loser.run(() -> {
                throw new IllegalStateException("fiscal authority unreachable");
            }));
            await(loser.running);
            expireLease();
            Future<ResponseEntity<Closed>> b = executor.submit(() ->
                    taker.run(() -> ResponseEntity.ok(new Closed("taker", 5))));
            await(taker.running);

            loser.release.countDown(); // A fails while B owns the lease: A's release must not touch B's lease
            assertThatThrownBy(() -> a.get(10, TimeUnit.SECONDS)).hasRootCauseMessage("fiscal authority unreachable");
            assertThatThrownBy(() -> idempotency.executeSteps(scope, key, target, body, Closed.class,
                    () -> ResponseEntity.ok(new Closed("must not run", 0))))
                    .isInstanceOf(IdempotentRequestInProgressException.class);

            taker.release.countDown();
            assertThat(b.get(10, TimeUnit.SECONDS).getBody()).isEqualTo(new Closed("taker", 5));
        }
        assertThat(storedStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void anOwnerWhoseLeaseExpiredWithNoTakerStillCompletes() throws Exception {
        Blocked owner = new Blocked();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ResponseEntity<Closed>> a = executor.submit(() ->
                    owner.run(() -> ResponseEntity.ok(new Closed("slow but alone", 4))));
            await(owner.running);
            expireLease(); // nobody takes it over

            owner.release.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).getBody()).isEqualTo(new Closed("slow but alone", 4));
        }
        assertThat(storedStatus()).isEqualTo("COMPLETED");
        assertThat(idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> ResponseEntity.ok(new Closed("must not run", 0))).getBody())
                .isEqualTo(new Closed("slow but alone", 4));
    }

    @Test
    void anOwnerThatLostItsLeaseAndCompletesFirstStoresNothingTheTakersResponseIsStored() throws Exception {
        Blocked loser = new Blocked();
        Blocked taker = new Blocked();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<Closed>> a = executor.submit(() ->
                    loser.run(() -> ResponseEntity.ok(new Closed("loser", 99))));
            await(loser.running);
            expireLease();
            Future<ResponseEntity<Closed>> b = executor.submit(() ->
                    taker.run(() -> ResponseEntity.ok(new Closed("taker", 5))));
            await(taker.running);

            loser.release.countDown(); // A completes before B: nothing is stored yet, so A returns its own response
            assertThat(a.get(10, TimeUnit.SECONDS).getBody()).isEqualTo(new Closed("loser", 99));
            assertThat(storedStatus()).isEqualTo("IN_PROGRESS");

            taker.release.countDown();
            assertThat(b.get(10, TimeUnit.SECONDS).getBody()).isEqualTo(new Closed("taker", 5));
        }
        assertThat(idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> ResponseEntity.ok(new Closed("must not run", 0))).getBody()).isEqualTo(new Closed("taker", 5));
    }

    @Test
    void aSneakyThrownCheckedExceptionAlsoReleasesTheLease() {
        assertThatThrownBy(() -> idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> sneakyThrow(new IOException("socket closed"))))
                .isInstanceOf(IOException.class).hasMessage("socket closed");

        assertThat(idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> ResponseEntity.ok(new Closed("CLOSED", 4))).getBody()).isEqualTo(new Closed("CLOSED", 4));
    }

    @Test
    void aFailedStepReleasesTheLeaseSoAnImmediateRetryRunsTheStepsAgain() {
        assertThatThrownBy(() -> idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
            throw new IllegalStateException("fiscal authority unreachable");
        })).hasMessage("fiscal authority unreachable");
        assertThat(storedStatus()).isEqualTo("IN_PROGRESS"); // nothing stored (D47.3)

        ResponseEntity<Closed> retry = idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> ResponseEntity.ok(new Closed("CLOSED", 4)));

        assertThat(retry.getBody()).isEqualTo(new Closed("CLOSED", 4));
        assertThat(storedStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void aNon2xxResponseIsAProgrammingErrorStoresNothingAndReleasesTheLease() {
        assertThatThrownBy(() -> idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> ResponseEntity.unprocessableContent().body(new Closed("FISCAL_PENDING", 3))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2xx");

        assertThat(idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> ResponseEntity.ok(new Closed("CLOSED", 4))).getBody()).isEqualTo(new Closed("CLOSED", 4));
    }

    @Test
    void theStepsRunWithNoTransactionAndNoConnectionBoundAndMayOpenTheirOwn() {
        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        AtomicReference<Boolean> resourceBound = new AtomicReference<>();
        AtomicReference<Boolean> stepTransactionActive = new AtomicReference<>();

        idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            resourceBound.set(TransactionSynchronizationManager.getResourceMap().values().stream()
                    .anyMatch(ResourceHolder.class::isInstance));
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    stepTransactionActive.set(TransactionSynchronizationManager.isActualTransactionActive()));
            return ResponseEntity.ok(new Closed("CLOSED", 4));
        });

        assertThat(transactionActive.get()).isFalse();
        assertThat(resourceBound.get()).isFalse();
        assertThat(stepTransactionActive.get()).isTrue();
    }

    @Test
    void callingItInsideATransactionFailsFastWithoutClaimingTheKey() {
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
                    runs.incrementAndGet();
                    return ResponseEntity.ok(new Closed("must not run", 0));
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside");

        assertThat(runs).hasValue(0);
        assertThat(jdbc.sql("SELECT count(*) FROM shared.idempotency_record WHERE scope = :s")
                .param("s", scope).query(Long.class).single()).isZero();
    }

    @Test
    void theSameKeyWithADifferentBodyOrTargetIsRejectedWith422() {
        idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> ResponseEntity.ok(new Closed("CLOSED", 4)));
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> idempotency.executeSteps(scope, key, target, Map.of("orderVersion", 4),
                Closed.class, () -> {
                    runs.incrementAndGet();
                    return ResponseEntity.ok(new Closed("must not run", 0));
                })).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> idempotency.executeSteps(scope, key, target + "/other", body, Closed.class, () -> {
            runs.incrementAndGet();
            return ResponseEntity.ok(new Closed("must not run", 0));
        })).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(runs).hasValue(0);
    }

    @Test
    void aDifferentBodyIsRejectedWith422EvenWhileTheLeaseIsLive() {
        insertAbandonedClaim(Instant.now().plusSeconds(30));

        assertThatThrownBy(() -> idempotency.executeSteps(scope, key, target, Map.of("orderVersion", 4),
                Closed.class, () -> ResponseEntity.ok(new Closed("must not run", 0))))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void aScopeUsesOneModeOnlyAnUnfinishedExecuteClaimIsRefused() {
        jdbc.sql("""
                        INSERT INTO shared.idempotency_record (scope, key, request_hash, status, created_at)
                        VALUES (:s, :k, :h, 'IN_PROGRESS', :created)
                        """)
                .param("s", scope).param("k", key).param("h", hashOf(body))
                .param("created", Timestamp.from(Instant.now())).update();

        assertThatThrownBy(() -> idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> ResponseEntity.ok(new Closed("must not run", 0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one");
    }

    @Test
    void retentionKeepsAnOldClaimWhoseStepLeaseIsStillLive() {
        Instant now = Instant.now();
        insertClaim("old-live-lease", now.minus(Duration.ofDays(91)), now.plusSeconds(30));
        insertClaim("old-expired-lease", now.minus(Duration.ofDays(91)), now.minusSeconds(1));

        retention.purgeExpired();

        assertThat(jdbc.sql("SELECT key FROM shared.idempotency_record WHERE scope = :s")
                .param("s", scope).query(String.class).list())
                .containsExactly("old-live-lease");
    }

    private void insertAbandonedClaim(Instant leaseUntil) {
        jdbc.sql("""
                        INSERT INTO shared.idempotency_record
                            (scope, key, request_hash, status, created_at, lease_owner, lease_until)
                        VALUES (:s, :k, :h, 'IN_PROGRESS', :created, :owner, :until)
                        """)
                .param("s", scope).param("k", key).param("h", hashOf(body)).param("owner", UUID.randomUUID())
                .param("created", Timestamp.from(Instant.now()))
                .param("until", Timestamp.from(leaseUntil)).update();
    }

    private void insertClaim(String recordKey, Instant createdAt, Instant leaseUntil) {
        jdbc.sql("""
                        INSERT INTO shared.idempotency_record
                            (scope, key, request_hash, status, created_at, lease_owner, lease_until)
                        VALUES (:s, :k, :h, 'IN_PROGRESS', :created, :owner, :until)
                        """)
                .param("s", scope).param("k", recordKey).param("h", "0".repeat(64))
                .param("created", Timestamp.from(createdAt)).param("owner", UUID.randomUUID())
                .param("until", Timestamp.from(leaseUntil)).update();
    }

    /** The request hash as the service computes it, read back from a probe record under another key. */
    private String hashOf(Map<String, Object> requestBody) {
        String probeKey = "probe-" + UUID.randomUUID();
        idempotency.executeSteps(scope, probeKey, target, requestBody, Closed.class,
                () -> ResponseEntity.ok(new Closed("probe", 0)));
        return jdbc.sql("SELECT request_hash FROM shared.idempotency_record WHERE scope = :s AND key = :k")
                .param("s", scope).param("k", probeKey).query(String.class).single();
    }

    private void expireLease() {
        jdbc.sql("""
                        UPDATE shared.idempotency_record SET lease_until = :past
                        WHERE scope = :s AND key = :k""")
                // the app Clock decides expiry: expire with a Java instant, not the database clock (no VM skew)
                .param("past", Timestamp.from(Instant.now().minusSeconds(5)))
                .param("s", scope).param("k", key).update();
    }

    /** executeSteps for this test's key, whose steps signal {@code running}, then wait for {@code release}. */
    private final class Blocked {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        ResponseEntity<Closed> run(Supplier<ResponseEntity<Closed>> outcome) {
            return idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
                running.countDown();
                await(release);
                return outcome.get();
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable checked) throws E {
        throw (E) checked;
    }

    private String storedStatus() {
        return jdbc.sql("SELECT status FROM shared.idempotency_record WHERE scope = :s AND key = :k")
                .param("s", scope).param("k", key).query(String.class).single();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    record Closed(String lifecycle, long version) {
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
