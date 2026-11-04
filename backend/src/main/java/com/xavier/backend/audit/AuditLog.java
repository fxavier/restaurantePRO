package com.xavier.backend.audit;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.audit.model.AuditEvent;
import com.xavier.backend.audit.repository.AuditEventRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit trail's only write path (D20, D41). Call it from a use case, inside that use case's transaction
 * ({@code MANDATORY}), so the change and its audit row commit or roll back together.
 *
 * <ul>
 *   <li>{@link #recordAsCurrentActor}: what every use case uses. Who acted (id and kind) and on which terminal come
 *       from the session ({@link AccessPolicy#current()}); never pass them by hand.</li>
 *   <li>{@link #recordAsSystem}: a write nobody asked for in a session (bootstrap, scheduled job, listener,
 *       an unauthenticated device enrolment). {@code actor_kind = SYSTEM}, no actor.</li>
 *   <li>{@link #recordAs}: only for login and logout, whose actor is not (or no longer) the current principal.</li>
 * </ul>
 *
 * <p>Arguments common to all three: {@code action} is a past-tense verb in UPPER_SNAKE ({@code STORE_UPDATED});
 * {@code entityType} the aggregate name from CONTEXT.md; {@code entityId} its id as text; {@code storeId} the store
 * it concerns or {@code null}; {@code reason} the user's free text or {@code null}; {@code details} small flat facts,
 * never PINs, passwords, tokens or card data (secret-looking top-level keys are rejected).
 */
@Service
public class AuditLog {

    static final String SYSTEM = "SYSTEM";

    private final AuditEventRepository events;
    private final JsonMapper json;
    private final Clock clock;
    private final AccessPolicy accessPolicy;

    AuditLog(AuditEventRepository events, JsonMapper json, Clock clock, AccessPolicy accessPolicy) {
        this.events = events;
        this.json = json;
        this.clock = clock;
        this.accessPolicy = accessPolicy;
    }

    /**
     * @param approverId the supervisor whose credentials authorized it (D28, D40), or {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAsCurrentActor(String action, String entityType, String entityId, UUID storeId,
                                     UUID approverId, String reason, Map<String, Object> details) {
        Actor actor = accessPolicy.current();
        record(new AuditEntry(actor.userId(), actor.kind().name(), approverId, action, entityType, entityId, storeId,
                actor.terminalId(), reason, details));
    }

    /**
     * @param terminalId the terminal it happened on (a device enrolling, a print job), or {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAsSystem(String action, String entityType, String entityId, UUID storeId, UUID terminalId,
                               String reason, Map<String, Object> details) {
        record(new AuditEntry(null, SYSTEM, null, action, entityType, entityId, storeId, terminalId, reason,
                details));
    }

    /** Login and logout only: {@code actor} is the session being started or ended. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAs(Actor actor, String action, String entityType, String entityId, UUID storeId,
                         String reason, Map<String, Object> details) {
        record(new AuditEntry(actor.userId(), actor.kind().name(), null, action, entityType, entityId, storeId,
                actor.terminalId(), reason, details));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void record(AuditEntry entry) {
        events.save(new AuditEvent(clock.instant(), entry.actorId(), entry.actorKind(), entry.approverId(),
                entry.action(), entry.entityType(), entry.entityId(), entry.storeId(), entry.terminalId(),
                entry.reason(), json.writeValueAsString(entry.details())));
    }
}
