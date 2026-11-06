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
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.data.domain.Persistable;
import org.hibernate.type.SqlTypes;

/**
 * One imported provider statement (D78): of a store and a payment method, over the business days
 * {@code [periodFrom, periodTo]}, identified by the SHA-256 of its content ({@code UNIQUE(method_code, file_sha256)}:
 * a statement is imported once). A fact: the database refuses UPDATE and DELETE.
 */
@Entity
@Immutable
@Table(schema = "settlement", name = "reconciliation_batch")
public class ReconciliationBatch implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false, length = 30)
    private String methodCode;

    @Column(nullable = false, updatable = false)
    private LocalDate periodFrom;

    @Column(nullable = false, updatable = false)
    private LocalDate periodTo;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "file_sha256", nullable = false, updatable = false, length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String fileSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private BatchSource source;

    @Column(nullable = false, updatable = false)
    private int lineCount;

    @Column(nullable = false, updatable = false)
    private UUID importedByAccountId;

    @Column(nullable = false, updatable = false)
    private Instant importedAt;

    /** The id is assigned here, so Spring Data asks this instead of selecting before the insert. */
    @Transient
    private boolean stored;

    protected ReconciliationBatch() {
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

    public static ReconciliationBatch of(UUID storeId, String methodCode, LocalDate periodFrom, LocalDate periodTo,
                                         Currency currency, String fileSha256, BatchSource source, int lineCount,
                                         UUID importedBy, Instant now) {
        ReconciliationBatch batch = new ReconciliationBatch();
        batch.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        batch.storeId = Objects.requireNonNull(storeId, "storeId");
        batch.methodCode = Objects.requireNonNull(methodCode, "methodCode");
        batch.periodFrom = Objects.requireNonNull(periodFrom, "periodFrom");
        batch.periodTo = Objects.requireNonNull(periodTo, "periodTo");
        batch.currency = currency.getCurrencyCode();
        batch.fileSha256 = Objects.requireNonNull(fileSha256, "fileSha256");
        batch.source = Objects.requireNonNull(source, "source");
        batch.lineCount = lineCount;
        batch.importedByAccountId = Objects.requireNonNull(importedBy, "importedBy");
        batch.importedAt = now;
        return batch;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getMethodCode() {
        return methodCode;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public BatchSource getSource() {
        return source;
    }

    public int getLineCount() {
        return lineCount;
    }

    public UUID getImportedByAccountId() {
        return importedByAccountId;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
