package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.model.dto.UnitSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A sale unit (piece, kg…). */
@Entity
@Table(schema = "catalog", name = "unit")
public class Unit {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Unit() {
        // JPA
    }

    public static Unit create(String code, String name, Instant now) {
        Unit unit = new Unit();
        unit.createdAt = now;
        unit.update(code, name, now);
        return unit;
    }

    public void update(String code, String name, Instant now) {
        this.code = CatalogText.required(code, "code");
        this.name = CatalogText.required(name, "name");
        this.updatedAt = now;
    }

    public UnitSummary toSummary() {
        return new UnitSummary(id, code, name, version);
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
