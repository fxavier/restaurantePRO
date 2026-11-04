package com.xavier.backend.audit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** One audit row. Immutable in code (no setters, {@link Immutable}) and in the database (trigger). */
@Entity
@Immutable
@Table(schema = "audit", name = "audit_event")
public class AuditEvent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    private UUID actorId;

    @Column(length = 10)
    private String actorKind;

    private UUID approverId;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(nullable = false, length = 100)
    private String entityType;

    @Column(nullable = false, length = 100)
    private String entityId;

    private UUID storeId;

    private UUID terminalId;

    @Column(columnDefinition = "text")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String details;

    protected AuditEvent() {
        // JPA
    }

    public AuditEvent(Instant occurredAt, UUID actorId, String actorKind, UUID approverId, String action,
                      String entityType, String entityId, UUID storeId, UUID terminalId, String reason,
                      String detailsJson) {
        this.occurredAt = occurredAt;
        this.actorId = actorId;
        this.actorKind = actorKind;
        this.approverId = approverId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.storeId = storeId;
        this.terminalId = terminalId;
        this.reason = reason;
        this.details = detailsJson;
    }

    public UUID getId() {
        return id;
    }
}
