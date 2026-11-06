package com.xavier.backend.settlement.model;

import com.xavier.backend.settlement.model.dto.TaxCorrectionResolutionView;
import com.xavier.backend.settlement.model.dto.TaxRoundingAdjustmentView;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Where the tax cent of one drifting order line went (D96 rule 5): ABSORBED by a residual draft line of the same rate
 * ({@code targetLineId}, {@code targetPartitionId} {@code null} for a pool line), or a PENDING_CORRECTION of
 * {@code documentId}, which a correction process must amend. One per drifting line ({@code UNIQUE(order_id,
 * source_line_id)}); a later draft re-applies it. A fact: the database refuses UPDATE, DELETE and TRUNCATE.
 */
@Entity
@Immutable
@Table(schema = "settlement", name = "tax_rounding_adjustment")
public class TaxRoundingAdjustment implements Persistable<UUID> {

    public enum Kind { ABSORBED, PENDING_CORRECTION }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(nullable = false, updatable = false)
    private UUID sourceLineId;

    @Column(nullable = false, updatable = false, precision = 7, scale = 4)
    private BigDecimal ratePercent;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Kind kind;

    @Column(nullable = false, updatable = false)
    private UUID documentId;

    @Column(updatable = false)
    private UUID targetLineId;

    @Column(updatable = false)
    private UUID targetPartitionId;

    @Column(nullable = false, updatable = false, length = 10)
    private String actorKind;

    @Column(updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean stored;

    protected TaxRoundingAdjustment() {
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

    /** Who recorded it: {@code SYSTEM} has no id; a person ({@code ACCOUNT}, {@code EMPLOYEE}) has one. */
    public record Recorder(String kind, UUID id) {
    }

    /** δ of {@code sourceLineId} taken by the residual line of {@code targetLineId}'s {@code targetPartitionId} share. */
    public static TaxRoundingAdjustment absorbed(UUID orderId, UUID storeId, LocalDate businessDate, UUID sourceLineId,
                                                 BigDecimal ratePercent, Money amount, UUID residualDocumentId,
                                                 UUID targetLineId, UUID targetPartitionId, Recorder by,
                                                 Instant now) {
        TaxRoundingAdjustment row = create(orderId, storeId, businessDate, sourceLineId, ratePercent, amount,
                Kind.ABSORBED, residualDocumentId, by, now);
        row.targetLineId = Objects.requireNonNull(targetLineId, "targetLineId");
        row.targetPartitionId = targetPartitionId;
        return row;
    }

    /** δ of {@code sourceLineId} nothing could take: {@code documentId} needs the market's correction process. */
    public static TaxRoundingAdjustment pendingCorrection(UUID orderId, UUID storeId, LocalDate businessDate,
                                                          UUID sourceLineId, BigDecimal ratePercent, Money amount,
                                                          UUID documentId, Recorder by, Instant now) {
        return create(orderId, storeId, businessDate, sourceLineId, ratePercent, amount, Kind.PENDING_CORRECTION,
                documentId, by, now);
    }

    private static TaxRoundingAdjustment create(UUID orderId, UUID storeId, LocalDate businessDate, UUID sourceLineId,
                                                BigDecimal ratePercent, Money amount, Kind kind, UUID documentId,
                                                Recorder by, Instant now) {
        if (amount.amount().signum() == 0) {
            throw new IllegalArgumentException("A tax rounding adjustment is never zero");
        }
        TaxRoundingAdjustment row = new TaxRoundingAdjustment();
        row.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        row.orderId = Objects.requireNonNull(orderId, "orderId");
        row.storeId = Objects.requireNonNull(storeId, "storeId");
        row.businessDate = Objects.requireNonNull(businessDate, "businessDate");
        row.sourceLineId = Objects.requireNonNull(sourceLineId, "sourceLineId");
        row.ratePercent = Objects.requireNonNull(ratePercent, "ratePercent");
        row.amount = amount.amount();
        row.currency = amount.currency().getCurrencyCode();
        row.kind = kind;
        row.documentId = Objects.requireNonNull(documentId, "documentId");
        row.actorKind = by.kind();
        row.actorId = by.id();
        row.createdAt = now;
        return row;
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public boolean isAbsorbed() {
        return kind == Kind.ABSORBED;
    }

    /** @param resolution its correction resolution, or {@code null} (always {@code null} for ABSORBED) */
    public TaxRoundingAdjustmentView toView(TaxCorrectionResolutionView resolution) {
        return new TaxRoundingAdjustmentView(id, orderId, storeId, businessDate, sourceLineId,
                ratePercent.stripTrailingZeros(), amount(), kind.name(), documentId, targetLineId, targetPartitionId,
                actorKind, actorId, createdAt, resolution);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getSourceLineId() {
        return sourceLineId;
    }

    public BigDecimal getRatePercent() {
        return ratePercent;
    }

    public Kind getKind() {
        return kind;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getTargetLineId() {
        return targetLineId;
    }

    public UUID getTargetPartitionId() {
        return targetPartitionId;
    }
}
