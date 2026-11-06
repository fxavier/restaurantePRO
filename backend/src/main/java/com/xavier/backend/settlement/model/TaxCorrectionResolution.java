package com.xavier.backend.settlement.model;

import com.xavier.backend.settlement.model.dto.TaxCorrectionResolutionView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * How a pending fiscal correction was cleared (D96 rule 6): CORRECTED by the corrective document
 * {@code correctionReference} names. One per PENDING_CORRECTION adjustment (the database refuses any other). A fact.
 */
@Entity
@Immutable
@Table(schema = "settlement", name = "tax_correction_resolution")
public class TaxCorrectionResolution implements Persistable<UUID> {

    public static final String CORRECTED = "CORRECTED";
    static final int MAX_REFERENCE = 100;
    static final int MAX_REASON = 500;

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID adjustmentId;

    @Column(nullable = false, updatable = false, length = 20)
    private String action;

    @Column(nullable = false, updatable = false, length = MAX_REFERENCE)
    private String correctionReference;

    @Column(nullable = false, updatable = false, length = MAX_REASON)
    private String reason;

    @Column(nullable = false, updatable = false, length = 10)
    private String actorKind;

    @Column(updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    @Transient
    private boolean stored;

    protected TaxCorrectionResolution() {
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

    /** A pending correction of {@code adjustment}, CORRECTED by the document {@code correctionReference}. */
    public static TaxCorrectionResolution corrected(TaxRoundingAdjustment adjustment, String correctionReference,
                                                    String reason, TaxRoundingAdjustment.Recorder by, Instant now) {
        if (adjustment.isAbsorbed()) {
            throw new IllegalArgumentException("An ABSORBED cent needs no correction: " + adjustment.getId());
        }
        TaxCorrectionResolution resolution = new TaxCorrectionResolution();
        resolution.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        resolution.adjustmentId = adjustment.getId();
        resolution.action = CORRECTED;
        resolution.correctionReference = required(correctionReference, "correctionReference", MAX_REFERENCE);
        resolution.reason = required(reason, "reason", MAX_REASON);
        resolution.actorKind = Objects.requireNonNull(by.kind(), "actorKind");
        resolution.actorId = by.id();
        resolution.at = now;
        return resolution;
    }

    private static String required(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String text = value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public TaxCorrectionResolutionView toView() {
        return new TaxCorrectionResolutionView(id, adjustmentId, action, correctionReference, reason, actorKind,
                actorId, at);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getAdjustmentId() {
        return adjustmentId;
    }

    public String getCorrectionReference() {
        return correctionReference;
    }

    public String getReason() {
        return reason;
    }
}
