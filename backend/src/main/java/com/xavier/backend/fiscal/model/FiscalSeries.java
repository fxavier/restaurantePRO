package com.xavier.backend.fiscal.model;

import com.xavier.backend.fiscal.exception.IllegalSeriesStateException;
import com.xavier.backend.fiscal.exception.InvalidSeriesException;
import com.xavier.backend.fiscal.model.dto.SeriesView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A per-store numbering series of one document type (spec §8.3, D50 §7.6). Numbers are taken only under the
 * series row lock inside the issuing transaction, so a rollback gives the number back: the sequence has no gaps.
 * {@code nextNumber} has no API (spec §9): the first number is set at creation and only issuing advances it.
 */
@Entity
@Table(schema = "fiscal", name = "fiscal_series")
public class FiscalSeries {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private DocumentType documentType;

    @Column(nullable = false, updatable = false, length = 20)
    private String code;

    @Column(nullable = false, updatable = false)
    private long firstNumber;

    @Column(nullable = false)
    private long nextNumber;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected FiscalSeries() {
        // JPA
    }

    public static FiscalSeries create(UUID storeId, DocumentType documentType, String code, long firstNumber,
                                      Instant now) {
        if (code == null || code.isBlank()) {
            throw new InvalidSeriesException("code is required");
        }
        if (firstNumber < 1) {
            throw new InvalidSeriesException("firstNumber must be at least 1, was " + firstNumber);
        }
        FiscalSeries series = new FiscalSeries();
        series.storeId = storeId;
        series.documentType = documentType;
        series.code = code.strip();
        series.firstNumber = firstNumber;
        series.nextNumber = firstNumber;
        series.active = true;
        series.createdAt = now;
        series.updatedAt = now;
        return series;
    }

    /** The number of the document being issued; call only while holding the series row lock. */
    public long takeNextNumber(Instant now) {
        requireActive();
        long number = nextNumber;
        nextNumber = number + 1;
        updatedAt = now;
        return number;
    }

    public void deactivate(Instant now) {
        requireActive();
        active = false;
        updatedAt = now;
    }

    private void requireActive() {
        if (!active) {
            throw new IllegalSeriesStateException("Series " + code + " is inactive");
        }
    }

    public SeriesView toView() {
        return new SeriesView(id, storeId, documentType.name(), code, firstNumber, nextNumber, active, version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public String getCode() {
        return code;
    }

    public long getFirstNumber() {
        return firstNumber;
    }

    public long getNextNumber() {
        return nextNumber;
    }

    public boolean isActive() {
        return active;
    }
}
