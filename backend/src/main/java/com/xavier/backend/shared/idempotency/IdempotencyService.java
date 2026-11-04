package com.xavier.backend.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one Idempotency-Key mechanism (D24, spec §9). Controllers of repeatable commands call
 * {@link #execute}; nobody writes their own.
 *
 * <pre>{@code
 * @PostMapping("/releases/{id}/publish")
 * ResponseEntity<?> publish(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
 *                           @Valid @RequestBody PublishRequest body) {
 *     UUID actor = accessPolicy.current().userId();
 *     return idempotency.execute("catalog.release.publish:" + actor, key, body,
 *             () -> ResponseEntity.ok(releaseService.publish(id, body)));
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Scope = operation name + actor, so two users never share a key space.</li>
 *   <li>Same scope+key+body: the stored status and body are replayed; the action does not run again.</li>
 *   <li>Same scope+key, different body: {@link IdempotencyKeyReusedException} (422).</li>
 *   <li>Same scope+key while the first is still running: {@link IdempotentRequestInProgressException} (409).
 *       The primary key on (scope, key) decides the winner of a race.</li>
 *   <li>The action and the stored response commit in one transaction, so a stored response always
 *       means the effect happened. If the action throws, the key is released and may be retried.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyRecordStore store;
    private final JsonMapper canonicalJson;
    private final Clock clock;
    private final TransactionTemplate inOwnTransaction;
    private final TransactionTemplate inActionTransaction;

    IdempotencyService(IdempotencyRecordStore store, JsonMapper jsonMapper, Clock clock,
                       PlatformTransactionManager transactionManager) {
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

    public ResponseEntity<?> execute(String scope, String key, Object requestBody, Supplier<ResponseEntity<?>> action) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(action, "action");
        requireValidKey(key);
        String hash = hash(requestBody);

        Boolean claimed = inOwnTransaction.execute(tx -> store.claim(scope, key, hash, clock.instant()));
        if (!Boolean.TRUE.equals(claimed)) {
            return replay(scope, key, hash);
        }
        try {
            return inActionTransaction.execute(tx -> {
                ResponseEntity<?> response = action.get();
                store.complete(scope, key, response.getStatusCode().value(),
                        canonicalJson.writeValueAsString(response.getBody()), clock.instant());
                return response;
            });
        } catch (RuntimeException | Error e) {
            inOwnTransaction.executeWithoutResult(tx -> store.release(scope, key));
            throw e;
        }
    }

    private ResponseEntity<?> replay(String scope, String key, String hash) {
        IdempotencyRecordStore.StoredRecord stored = store.find(scope, key)
                // released between our claim attempt and this read: the first request failed
                .orElseThrow(IdempotentRequestInProgressException::new);
        if (!stored.requestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException();
        }
        if (!stored.completed()) {
            throw new IdempotentRequestInProgressException();
        }
        JsonNode body = canonicalJson.readTree(stored.responseBody());
        return ResponseEntity.status(stored.responseStatus()).body(body.isNull() ? null : body);
    }

    private String hash(Object requestBody) {
        byte[] canonical = canonicalJson.writeValueAsString(requestBody).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JVM", e);
        }
    }

    private static void requireValidKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be present and at most " + MAX_KEY_LENGTH + " characters.");
        }
    }
}
