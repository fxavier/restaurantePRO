package com.xavier.backend.settlement.reconciliation.model;

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
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.data.domain.Persistable;

/**
 * What matching found for one statement line, or for one payment of the period the statement lacks (D78): its
 * {@link EntryKind}, the payment, our net amount and the provider's amount and fee. The database derives
 * {@code difference = provider − internal} (absent counting as zero), checks the shape of each kind, and lets a
 * payment be MATCHED or AMOUNT_MISMATCH once across every batch. A fact: never updated or deleted.
 */
@Entity
@Immutable
@Table(schema = "settlement", name = "reconciliation_entry")
public class ReconciliationEntry implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID batchId;

    @Column(updatable = false)
    private Integer lineNo;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private EntryKind kind;

    @Column(updatable = false)
    private UUID paymentId;

    @Column(updatable = false, length = 100)
    private String providerReference;

    @Column(updatable = false, length = 50)
    private String authorizationCode;

    @Column(updatable = false, length = 100)
    private String lineRef;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(updatable = false, precision = 19, scale = 4)
    private BigDecimal internalAmount;

    @Column(updatable = false, precision = 19, scale = 4)
    private BigDecimal providerAmount;

    @Column(updatable = false, precision = 19, scale = 4)
    private BigDecimal feeAmount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** The id is assigned here, so Spring Data asks this instead of selecting before the insert. */
    @Transient
    private boolean stored;

    protected ReconciliationEntry() {
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

    /** The entry of {@code match} in {@code batch}; a MISSING_PROVIDER entry occurred when its payment was confirmed. */
    public static ReconciliationEntry of(ReconciliationBatch batch, Match match, Instant now) {
        ReconciliationEntry entry = new ReconciliationEntry();
        entry.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        entry.batchId = batch.getId();
        entry.storeId = batch.getStoreId();
        entry.kind = match.kind();
        entry.paymentId = match.payment() == null ? null : match.payment().paymentId();
        StatementRow row = match.row();
        if (row != null) {
            entry.lineNo = row.lineNo();
            entry.providerReference = row.reference();
            entry.authorizationCode = row.authorizationCode();
            entry.lineRef = row.lineRef();
            entry.occurredAt = row.occurredAt();
            entry.providerAmount = row.amount().amount();
            entry.feeAmount = row.fee().amount();
        } else {
            entry.occurredAt = match.payment().confirmedAt();
        }
        entry.internalAmount = match.internalAmount() == null ? null : match.internalAmount().amount();
        entry.createdAt = now;
        return entry;
    }

    /** As the database derives it: provider − internal, an absent side counting as zero. */
    public Money difference(Currency currency) {
        BigDecimal provider = providerAmount == null ? BigDecimal.ZERO : providerAmount;
        BigDecimal internal = internalAmount == null ? BigDecimal.ZERO : internalAmount;
        return Money.of(provider.subtract(internal), currency);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public EntryKind getKind() {
        return kind;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getAuthorizationCode() {
        return authorizationCode;
    }

    public String getLineRef() {
        return lineRef;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public BigDecimal getInternalAmount() {
        return internalAmount;
    }

    public BigDecimal getProviderAmount() {
        return providerAmount;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }
}
