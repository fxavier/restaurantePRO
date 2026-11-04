package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.exception.IllegalReleaseStateException;
import com.xavier.backend.catalog.model.dto.ReleaseSummary;
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
 * A catalog publication of one store (D31, spec §8.3). Created as DRAFT, validated against the working copy,
 * then published: the complete catalog is frozen as {@code snapshot} (jsonb) with a per-store version, the
 * effective instant and the author. Publishing the next release retires this one. Once published, the
 * snapshot, version, instant and author never change (also enforced by a database trigger).
 */
@Entity
@Table(schema = "catalog", name = "configuration_release")
public class ConfigurationRelease {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReleaseStatus status;

    private Long catalogVersion;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String snapshot;

    private Instant effectiveAt;

    private UUID publishedBy;

    @Column(nullable = false, updatable = false)
    private UUID createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant validatedAt;

    private Instant retiredAt;

    @Version
    private Long lockVersion;

    protected ConfigurationRelease() {
        // JPA
    }

    public static ConfigurationRelease draft(UUID storeId, UUID createdBy, Instant now) {
        ConfigurationRelease release = new ConfigurationRelease();
        release.storeId = storeId;
        release.createdBy = createdBy;
        release.createdAt = now;
        release.status = ReleaseStatus.DRAFT;
        return release;
    }

    /** The working copy passed validation. Allowed again on a VALIDATED release after more edits. */
    public void markValidated(Instant now) {
        requireStatus("validate", Set.of(ReleaseStatus.DRAFT, ReleaseStatus.VALIDATED));
        status = ReleaseStatus.VALIDATED;
        validatedAt = now;
    }

    public void publish(long catalogVersion, String snapshot, UUID publishedBy, Instant now) {
        requireStatus("publish", Set.of(ReleaseStatus.VALIDATED));
        this.catalogVersion = catalogVersion;
        this.snapshot = snapshot;
        this.publishedBy = publishedBy;
        this.effectiveAt = now;
        this.status = ReleaseStatus.PUBLISHED;
    }

    /** Replaced by a newer publication of the same store. The frozen snapshot is kept unchanged. */
    public void retire(Instant now) {
        requireStatus("retire", Set.of(ReleaseStatus.PUBLISHED));
        status = ReleaseStatus.RETIRED;
        retiredAt = now;
    }

    private void requireStatus(String transition, Set<ReleaseStatus> allowed) {
        if (!allowed.contains(status)) {
            throw new IllegalReleaseStateException(
                    "Cannot " + transition + " release " + id + ": it is " + status + ", needs " + allowed);
        }
    }

    public ReleaseSummary toSummary() {
        return new ReleaseSummary(id, storeId, status.name(), catalogVersion, effectiveAt, publishedBy, createdBy,
                createdAt, validatedAt, retiredAt);
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

    public Long getCatalogVersion() {
        return catalogVersion;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }
}
