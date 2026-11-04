package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.model.dto.FamilySummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A family of items for POS navigation; with a {@code parentId} it is a subfamily (one level, spec §5). */
@Entity
@Table(schema = "catalog", name = "family")
public class Family {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    private UUID parentId;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Family() {
        // JPA
    }

    public static Family create(String code, String name, UUID parentId, Instant now) {
        Family family = new Family();
        family.createdAt = now;
        family.update(code, name, parentId, now);
        return family;
    }

    public void update(String code, String name, UUID parentId, Instant now) {
        this.code = CatalogText.required(code, "code");
        this.name = CatalogText.required(name, "name");
        this.parentId = parentId;
        this.updatedAt = now;
    }

    public boolean isSubfamily() {
        return parentId != null;
    }

    public FamilySummary toSummary() {
        return new FamilySummary(id, code, name, parentId, version);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Long getVersion() {
        return version;
    }
}
