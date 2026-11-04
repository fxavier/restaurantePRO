package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.model.dto.TaxCategorySummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A configurable tax category (no country is seeded); its rates are {@link TaxRate} rows. */
@Entity
@Table(schema = "catalog", name = "tax_category")
public class TaxCategory {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TaxCategory() {
        // JPA
    }

    public static TaxCategory create(String code, String name, Instant now) {
        TaxCategory category = new TaxCategory();
        category.createdAt = now;
        category.update(code, name, now);
        return category;
    }

    public void update(String code, String name, Instant now) {
        this.code = CatalogText.required(code, "code");
        this.name = CatalogText.required(name, "name");
        this.updatedAt = now;
    }

    /** {@code rates} ordered by effective instant. */
    public TaxCategorySummary toSummary(List<TaxRate> rates) {
        return new TaxCategorySummary(id, code, name, rates.stream().map(TaxRate::toSummary).toList(), version);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Long getVersion() {
        return version;
    }
}
