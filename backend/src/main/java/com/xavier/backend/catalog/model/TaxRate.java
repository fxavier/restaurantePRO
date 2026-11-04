package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.dto.TaxRateSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * The rate of a tax category from {@code effectiveFrom} on, as a decimal percent (16 = 16%). A rate change is
 * a new row with a later instant; rows are never edited.
 */
@Entity
@Immutable
@Table(schema = "catalog", name = "tax_rate")
public class TaxRate {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID taxCategoryId;

    @Column(nullable = false, updatable = false, precision = 7, scale = 4)
    private BigDecimal ratePercent;

    @Column(nullable = false, updatable = false)
    private Instant effectiveFrom;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected TaxRate() {
        // JPA
    }

    public static TaxRate of(UUID taxCategoryId, BigDecimal ratePercent, Instant effectiveFrom, Instant now) {
        CatalogText.required(ratePercent, "ratePercent");
        if (ratePercent.signum() < 0 || ratePercent.compareTo(HUNDRED) > 0 || ratePercent.stripTrailingZeros().scale() > 4) {
            throw new InvalidCatalogEntryException("ratePercent must be between 0 and 100 with at most 4 decimals");
        }
        TaxRate rate = new TaxRate();
        rate.taxCategoryId = CatalogText.required(taxCategoryId, "taxCategoryId");
        rate.ratePercent = ratePercent;
        rate.effectiveFrom = CatalogText.required(effectiveFrom, "effectiveFrom");
        rate.createdAt = now;
        return rate;
    }

    /** The rate in effect at {@code instant}: the latest one that started at or before it. */
    public static Optional<TaxRate> inEffectAt(Collection<TaxRate> rates, Instant instant) {
        return rates.stream()
                .filter(rate -> !rate.effectiveFrom.isAfter(instant))
                .max(Comparator.comparing(TaxRate::getEffectiveFrom));
    }

    /** The percent without insignificant zeros, e.g. {@code 16} or {@code 17.5}. */
    public BigDecimal getRatePercent() {
        BigDecimal stripped = ratePercent.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    public TaxRateSummary toSummary() {
        return new TaxRateSummary(id, getRatePercent(), effectiveFrom);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaxCategoryId() {
        return taxCategoryId;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }
}
