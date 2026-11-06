package com.xavier.backend.settlement.reconciliation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.data.domain.Persistable;

/**
 * The one decision about a reconciliation entry (D78; {@code UNIQUE(entry_id)}): the system's AUTO_MATCHED for a
 * MATCHED entry, or a person's ACCEPTED, REFUNDED_AT_PROVIDER or ESCALATED with a reason. A fact.
 */
@Entity
@Immutable
@Table(schema = "settlement", name = "reconciliation_resolution")
public class ReconciliationResolution implements Persistable<UUID> {

    static final int MAX_REASON = 500;
    static final String SYSTEM = "SYSTEM";

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID entryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 25)
    private ResolutionAction action;

    @Column(updatable = false, length = MAX_REASON)
    private String reason;

    @Column(nullable = false, updatable = false, length = 10)
    private String actorKind;

    @Column(updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    /** The id is assigned here, so Spring Data asks this instead of selecting before the insert. */
    @Transient
    private boolean stored;

    protected ReconciliationResolution() {
    }

    @PostLoad
    @PostPersist
    void markStored() {
        stored = true;
    }

    @Override
    public boolean isNew() {
        return !stored;
    }

    /** The system's resolution of a MATCHED entry at import. */
    public static ReconciliationResolution autoMatched(ReconciliationEntry entry, Instant now) {
        if (entry.getKind() != EntryKind.MATCHED) {
            throw new IllegalArgumentException("Only a MATCHED entry is resolved automatically: " + entry.getId());
        }
        return create(entry.getId(), ResolutionAction.AUTO_MATCHED, null, SYSTEM, null, now);
    }

    /** A person's decision, with a reason. */
    public static ReconciliationResolution by(UUID entryId, ResolutionAction action, String reason, String actorKind,
                                              UUID actorId, Instant now) {
        if (action == ResolutionAction.AUTO_MATCHED) {
            throw new IllegalArgumentException("AUTO_MATCHED is the system's");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A resolution needs a reason");
        }
        String text = reason.strip();
        return create(entryId, action, text.length() <= MAX_REASON ? text : text.substring(0, MAX_REASON),
                Objects.requireNonNull(actorKind, "actorKind"), Objects.requireNonNull(actorId, "actorId"), now);
    }

    private static ReconciliationResolution create(UUID entryId, ResolutionAction action, String reason,
                                                   String actorKind, UUID actorId, Instant now) {
        ReconciliationResolution resolution = new ReconciliationResolution();
        resolution.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        resolution.entryId = Objects.requireNonNull(entryId, "entryId");
        resolution.action = action;
        resolution.reason = reason;
        resolution.actorKind = actorKind;
        resolution.actorId = actorId;
        resolution.at = now;
        return resolution;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public ResolutionAction getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public String getActorKind() {
        return actorKind;
    }

    public UUID getActorId() {
        return actorId;
    }

    public Instant getAt() {
        return at;
    }
}
