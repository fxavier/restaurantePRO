package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.dto.MacroSummary;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A named list of item/variant/quantity entries that the POS adds as drafts in one step (D109). Company-wide; part of
 * the release.
 */
@Entity
@Table(schema = "catalog", name = "macro")
public class Macro {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @ElementCollection
    @CollectionTable(schema = "catalog", name = "macro_entry", joinColumns = @JoinColumn(name = "macro_id"))
    @OrderColumn(name = "position")
    private List<ItemLine> entries = new ArrayList<>();

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Macro() {
        // JPA
    }

    public static Macro create(String code, String name, List<ItemLine> entries, Instant now) {
        Macro macro = new Macro();
        macro.createdAt = now;
        macro.update(code, name, entries, now);
        return macro;
    }

    /** @throws InvalidCatalogEntryException (422) without entries or with a quantity that is not more than zero */
    public void update(String code, String name, List<ItemLine> entries, Instant now) {
        if (CatalogText.required(entries, "entries").isEmpty()) {
            throw new InvalidCatalogEntryException("A macro needs at least one entry");
        }
        if (entries.stream().anyMatch(entry -> entry.getQuantity().signum() <= 0)) {
            throw new InvalidCatalogEntryException("The quantity of a macro entry must be more than zero");
        }
        this.code = CatalogText.required(code, "code");
        this.name = CatalogText.required(name, "name");
        this.entries.clear();
        this.entries.addAll(entries);
        this.updatedAt = now;
    }

    public MacroSummary toSummary() {
        return new MacroSummary(id, code, name, entries.stream().map(entry -> new MacroSummary.Entry(entry.getItemId(),
                entry.getVariantId(), entry.getQuantity())).toList(), version);
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

    public List<ItemLine> getEntries() {
        return List.copyOf(entries);
    }

    public Long getVersion() {
        return version;
    }
}
