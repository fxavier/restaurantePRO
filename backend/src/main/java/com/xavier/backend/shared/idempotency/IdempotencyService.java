package com.xavier.backend.shared.idempotency;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.xavier.backend.shared.ExternalCalls;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one Idempotency-Key mechanism (D24, D47, spec §9). Controllers of repeatable commands call
 * {@link #execute}; nobody writes their own. Call it from the controller, outside any transaction: called
 * inside one it throws {@link IllegalStateException} before doing anything.
 *
 * <pre>{@code
 * @PostMapping("/releases/{id}/publish")
 * ResponseEntity<ReleaseSummary> publish(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
 *                                        @Valid @RequestBody PublishRequest body, HttpServletRequest request) {
 *     return idempotency.execute(accessPolicy.current().idempotencyScope("catalog.release.publish"), key,
 *             request.getMethod() + " " + request.getRequestURI(), body, ReleaseSummary.class,
 *             () -> ResponseEntity.ok(releaseService.publish(id, body)));
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code scope} = operation name + actor kind + actor id ({@code "<op>:<kind>:<id>"}, D38), so two
 *       principals never share a key space.</li>
 *   <li>{@code target} = what the request acts on, normally {@code "<METHOD> <resolved path>"}. The request
 *       identity is the SHA-256 of target + canonical JSON body ("hash do pedido"). Approver credentials travel
 *       in headers and are never part of it (D40).</li>
 *   <li>Same scope+key, same identity: the stored status, {@code Location} header and body are replayed, the body
 *       deserialized into {@code responseType} (D47.1, D47.2); the action does not run.</li>
 *   <li>Same scope+key, different target or body: {@link IdempotencyKeyReusedException} (422).</li>
 *   <li>Same scope+key while another request is running it: {@link IdempotentRequestInProgressException} (409).</li>
 *   <li>Only 2xx is stored (D47.3). Business errors are thrown, which rolls the action back, so a retry with the
 *       same key evaluates the rule again. An action that <em>returns</em> a non-2xx response is a programming
 *       error: {@link IllegalStateException}, and nothing is stored.</li>
 *   <li>Crash-safe: running the action requires holding the record's row lock inside the action's
 *       transaction, and the stored response commits in that same transaction. If the owner dies or
 *       its action throws, the transaction rolls back (effects and lock together) and the next request
 *       with that key takes the claim over and runs it once.</li>
 *   <li>The action runs in a {@code READ COMMITTED} database transaction (D47.5), so it must never call an external
 *       system: those use "persist, then call, then record" (D48, {@code shared.ExternalCalls}).</li>
 *   <li>Completed records are deleted after {@code restaurantpro.idempotency.retention} (D47.4,
 *       {@link IdempotencyRetention}).</li>
 * </ul>
 *
 * <p><b>Multi-step commands (D63).</b> A command that must call an external system (fiscal issue, provider status)
 * uses {@link #executeSteps} instead: the key is claimed with a lease, the steps run with no transaction and no
 * record lock (each step opens its own short transaction, and every step is idempotent by domain state), and the
 * response is stored when the lease owner completes. Each operation scope uses exactly one of the two modes: a
 * scope is either {@code execute} or {@code executeSteps}, never both ({@code executeSteps} refuses an unfinished
 * claim made by {@code execute} with {@link IllegalStateException}).
 */
@Service
public class IdempotencyService {

    static final int MAX_KEY_LENGTH = 255;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordStore store;
    private final JsonMapper canonicalJson;
    private final Clock clock;
    private final TransactionTemplate inOwnTransaction;
    private final TransactionTemplate inActionTransaction;
    private final Duration stepLease;

    IdempotencyService(IdempotencyRecordStore store, JsonMapper jsonMapper, Clock clock,
                       PlatformTransactionManager transactionManager,
                       @Value("${restaurantpro.idempotency.step-lease:PT30S}") Duration stepLease) {
        if (stepLease.isNegative() || stepLease.isZero()) {
            throw new IllegalArgumentException("restaurantpro.idempotency.step-lease must be positive: " + stepLease);
        }
        this.stepLease = stepLease;
        this.store = store;
        this.canonicalJson = jsonMapper.rebuild()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        this.clock = clock;
        this.inOwnTransaction = new TransactionTemplate(transactionManager);
        this.inOwnTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.inActionTransaction = new TransactionTemplate(transactionManager);
    }

    public <T> ResponseEntity<T> execute(String scope, String key, String target, Object requestBody,
                                         Class<T> responseType, Supplier<ResponseEntity<T>> action) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(action, "action");
        requireNoActiveTransaction();
        requireValidKey(key);
        String hash = hash(target, requestBody);

        boolean createdNow = Boolean.TRUE.equals(
                inOwnTransaction.execute(tx -> store.claim(scope, key, hash, clock.instant())));
        IdempotencyRecordStore.StoredRecord existing = store.find(scope, key)
                .orElseThrow(() -> new IllegalStateException("idempotency record vanished: " + scope));
        if (!existing.requestHash().equals(hash)) {
            log.warn("Idempotency key reused for a different request: scope={} key={}", scope, key);
            throw new IdempotencyKeyReusedException();
        }
        if (existing.completed()) {
            return replay(scope, key, existing, responseType);
        }
        return inActionTransaction.execute(tx -> {
            IdempotencyRecordStore.StoredRecord locked = store.lockWithoutWaiting(scope, key).orElseThrow(() -> {
                log.info("Idempotent request still in progress: scope={} key={}", scope, key);
                return new IdempotentRequestInProgressException();
            });
            if (locked.completed()) {
                return replay(scope, key, locked, responseType); // another request finished between our read and our lock
            }
            if (!createdNow) {
                // Either an earlier attempt died, or a concurrent duplicate claimed the key and has not locked
                // it yet (it will then get 409 or the replay). Both are normal, so no WARN and no crash claim.
                log.info("Idempotency key found an unfinished claim; this request runs it: scope={} key={}",
                        scope, key);
            }
            ResponseEntity<T> response = action.get();
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                // thrown inside the action's transaction, so its effects roll back with it (D47.3)
                throw new IllegalStateException("An idempotent action must return a 2xx response or throw; got "
                        + (response == null ? "null" : response.getStatusCode()) + " for scope " + scope);
            }
            URI location = response.getHeaders().getLocation();
            store.complete(scope, key, response.getStatusCode().value(),
                    canonicalJson.writeValueAsString(response.getBody()),
                    location == null ? null : location.toString(), clock.instant());
            return response;
        });
    }

    /**
     * D63: the Idempotency-Key mode for a command whose steps must run with no transaction (an external call).
     * Call it from the controller, outside any transaction, exactly like {@link #execute}; the hash, 422, replay,
     * {@code Location} and 2xx-only rules are the same.
     *
     * <ol>
     *   <li><b>Claim</b> (own short transaction): the record is created with this call as lease owner until
     *       now + {@code restaurantpro.idempotency.step-lease} (default {@code PT30S}). An unfinished claim with a
     *       live lease gives {@link IdempotentRequestInProgressException} (409); an expired one is taken over by
     *       compare-and-set, so of two concurrent takers exactly one runs the steps.</li>
     *   <li><b>Steps</b> run with no transaction bound and no lock on the record ({@link ExternalCalls} holds on
     *       entry and after they return). Each step opens its own short transaction, so the request holds at most
     *       one connection at a time. Every step must be idempotent by domain state: the lease only suppresses
     *       duplicate work, and a takeover while a slow first owner still runs is safe.</li>
     *   <li><b>Complete</b> (own short transaction): stored only while this call still owns the lease. If the lease
     *       was lost and the taker already stored its response, that response is replayed; otherwise this call's
     *       own response is returned (the same final state).</li>
     *   <li><b>Failure</b>: an exception (or a non-2xx response, an {@link IllegalStateException}) expires the lease
     *       at once and stores nothing, so a retry with the same key runs the steps again immediately. A crash
     *       leaves the lease to expire.</li>
     * </ol>
     */
    public <T> ResponseEntity<T> executeSteps(String scope, String key, String target, Object requestBody,
                                              Class<T> responseType, Supplier<ResponseEntity<T>> steps) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(steps, "steps");
        requireNoTransactionForSteps();
        requireValidKey(key);
        String hash = hash(target, requestBody);
        UUID owner = UUID.randomUUID();

        Instant claimedAt = clock.instant();
        boolean createdNow = Boolean.TRUE.equals(inOwnTransaction.execute(tx ->
                store.claimWithLease(scope, key, hash, owner, claimedAt, claimedAt.plus(stepLease))));
        if (!createdNow) {
            Optional<ResponseEntity<T>> replayed = takeOverOrReplay(scope, key, hash, owner, responseType);
            if (replayed.isPresent()) {
                return replayed.get();
            }
        }
        try {
            requireNoTransactionForSteps();
            ResponseEntity<T> response = steps.get();
            requireNoTransactionForSteps(); // a step must not leave a transaction or a connection bound
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Idempotent steps must return a 2xx response or throw; got "
                        + (response == null ? "null" : response.getStatusCode()) + " for scope " + scope);
            }
            URI location = response.getHeaders().getLocation();
            String responseJson = canonicalJson.writeValueAsString(response.getBody());
            boolean stored = Boolean.TRUE.equals(inOwnTransaction.execute(tx -> store.completeOwned(scope, key, owner,
                    response.getStatusCode().value(), responseJson, location == null ? null : location.toString(),
                    clock.instant())));
            if (stored) {
                return response;
            }
            IdempotencyRecordStore.StoredRecord current = store.find(scope, key).orElse(null);
            if (current != null && current.completed()) {
                log.info("Idempotency step lease was lost; replaying the response stored by the taker: scope={} "
                        + "key={}", scope, key);
                return replay(scope, key, current, responseType);
            }
            log.info("Idempotency step lease was lost and nothing is stored yet; returning this request's response: "
                    + "scope={} key={}", scope, key);
            return response;
        } catch (Throwable failure) { // also a checked exception thrown sneakily by a step: always release
            releaseLease(scope, key, owner, failure);
            throw failure; // precise rethrow: unchanged, and only what the try block can throw
        }
    }

    /** @return the replay when the key is already completed; empty when this call now owns the lease */
    private <T> Optional<ResponseEntity<T>> takeOverOrReplay(String scope, String key, String hash, UUID owner,
                                                             Class<T> responseType) {
        IdempotencyRecordStore.StoredRecord existing = store.find(scope, key)
                .orElseThrow(() -> new IllegalStateException("idempotency record vanished: " + scope));
        if (!existing.requestHash().equals(hash)) {
            log.warn("Idempotency key reused for a different request: scope={} key={}", scope, key);
            throw new IdempotencyKeyReusedException();
        }
        if (existing.completed()) {
            return Optional.of(replay(scope, key, existing, responseType));
        }
        if (!existing.leased()) {
            throw new IllegalStateException("Idempotency scope " + scope + " has an unfinished claim made by execute; "
                    + "an operation scope uses exactly one of execute and executeSteps (D63)");
        }
        Instant now = clock.instant();
        if (existing.leaseLiveAt(now)) {
            log.info("Idempotent request still in progress (live step lease): scope={} key={}", scope, key);
            throw new IdempotentRequestInProgressException();
        }
        boolean tookOver = Boolean.TRUE.equals(inOwnTransaction.execute(tx ->
                store.takeOverExpired(scope, key, owner, now, now.plus(stepLease))));
        if (!tookOver) {
            IdempotencyRecordStore.StoredRecord current = store.find(scope, key).orElse(null);
            if (current != null && current.completed()) {
                return Optional.of(replay(scope, key, current, responseType));
            }
            log.info("Idempotent request still in progress (another request took the lease over): scope={} key={}",
                    scope, key);
            throw new IdempotentRequestInProgressException();
        }
        log.info("Idempotency step lease expired; this request takes the claim over: scope={} key={}", scope, key);
        return Optional.empty();
    }

    private void releaseLease(String scope, String key, UUID owner, Throwable failure) {
        try {
            inOwnTransaction.executeWithoutResult(tx -> store.releaseOwned(scope, key, owner, clock.instant()));
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure); // the lease still expires on its own
        }
    }

    /** {@link ExternalCalls#requireNoTransaction()} with a message that names this service. */
    private static void requireNoTransactionForSteps() {
        try {
            ExternalCalls.requireNoTransaction();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("IdempotencyService.executeSteps must be called outside any transaction "
                    + "(from the controller), and its steps must not leave a transaction or connection bound", e);
        }
    }

    private <T> ResponseEntity<T> replay(String scope, String key, IdempotencyRecordStore.StoredRecord stored,
                                         Class<T> responseType) {
        log.info("Idempotent request replayed: scope={} key={}", scope, key);
        ResponseEntity.BodyBuilder replayed = ResponseEntity.status(stored.responseStatus());
        if (stored.responseLocation() != null) {
            replayed.location(URI.create(stored.responseLocation()));
        }
        return replayed.body(canonicalJson.readValue(stored.responseBody(), responseType));
    }

    private String hash(String target, Object requestBody) {
        String identity = target + "\n" + canonicalJson.writeValueAsString(requestBody);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JVM", e);
        }
    }

    /**
     * Inside a caller's transaction the claim would not be visible to a concurrent duplicate and the action
     * would commit (or roll back) with the caller instead of with its stored response. That breaks D24, so
     * it is a programming error, not something to tolerate.
     */
    private static void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "IdempotencyService.execute must be called outside any transaction (from the controller)");
        }
    }

    private static void requireValidKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be present and at most " + MAX_KEY_LENGTH + " characters.");
        }
    }
}
