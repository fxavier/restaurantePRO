package com.xavier.backend.promotions.model;

import com.xavier.backend.promotions.exception.PromotionConflictException;
import com.xavier.backend.promotions.model.dto.PromotionReleaseView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.UuidGenerator;

/**
 * A promotions publication of one store (D102, the D31 machine). Created as DRAFT, validated against the working copy,
 * then published: the store's promotions and discount limits are frozen as {@code snapshot} (jsonb) with a per-store
 * version, the effective instant and the author. Publishing the next release retires this one. Once published,
 * nothing of it changes but the retirement (also enforced by a database trigger).
 */
@Entity
@Table(schema = "promotions", name = "promotion_release")
public class PromotionRelease {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReleaseStatus status;

    private Long versionNo;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String snapshot;

    private Instant effectiveAt;

    private UUID publishedByAccountId;

    @Column(nullable = false, updatable = false)
    private UUID createdByAccountId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant validatedAt;

    private Instant retiredAt;

    @Version
    private Long version;

    protected PromotionRelease() {
        // JPA
    }

    public static PromotionRelease draft(UUID storeId, UUID createdBy, Instant now) {
        PromotionRelease release = new PromotionRelease();
        release.storeId = storeId;
        release.createdByAccountId = createdBy;
        release.createdAt = now;
        release.status = ReleaseStatus.DRAFT;
        return release;
    }

    /** Fails with 409 unless this release may be validated (DRAFT, or VALIDATED again after more edits). */
    public void requireValidatable() {
        requireStatus("validate", Set.of(ReleaseStatus.DRAFT, ReleaseStatus.VALIDATED));
    }

    /** Fails with 409 unless this release is VALIDATED. */
    public void requirePublishable() {
        requireStatus("publish", Set.of(ReleaseStatus.VALIDATED));
    }

    public void markValidated(Instant now) {
        requireValidatable();
        status = ReleaseStatus.VALIDATED;
        validatedAt = now;
    }

    public void publish(long versionNo, String snapshot, UUID publishedBy, Instant now) {
        requirePublishable();
        this.versionNo = versionNo;
        this.snapshot = snapshot;
        this.publishedByAccountId = publishedBy;
        this.effectiveAt = now;
        this.status = ReleaseStatus.PUBLISHED;
    }

    /** Replaced by a newer publication of the same store; the frozen snapshot is kept unchanged. */
    public void retire(Instant now) {
        requireStatus("retire", Set.of(ReleaseStatus.PUBLISHED));
        status = ReleaseStatus.RETIRED;
        retiredAt = now;
    }

    private void requireStatus(String transition, Set<ReleaseStatus> allowed) {
        if (!allowed.contains(status)) {
            throw PromotionConflictException.releaseState(id,
                    "cannot " + transition + " a " + status + " release, needs " + allowed.stream().sorted().toList());
        }
    }

    public PromotionReleaseView toView() {
        return new PromotionReleaseView(id, storeId, status.name(), versionNo, effectiveAt, publishedByAccountId,
                createdByAccountId, createdAt, validatedAt, retiredAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public ReleaseStatus getStatus() {
        return status;
    }

    public Long getVersionNo() {
        return versionNo;
    }

    public String getSnapshot() {
        return snapshot;
    }
}
