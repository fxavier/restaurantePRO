package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.dto.QuickPageSummary;
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

/** A page of POS buttons (D109) for one store, or for every store when {@code storeId} is null. Part of the release. */
@Entity
@Table(schema = "catalog", name = "quick_page")
public class QuickPage {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(updatable = false)
    private UUID storeId;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private int position;

    @ElementCollection
    @CollectionTable(schema = "catalog", name = "quick_page_entry", joinColumns = @JoinColumn(name = "page_id"))
    @OrderColumn(name = "position")
    private List<QuickPageEntry> entries = new ArrayList<>();

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected QuickPage() {
        // JPA
    }

    public static QuickPage create(UUID storeId, String code, String name, int position, List<QuickPageEntry> entries,
                                   Instant now) {
        QuickPage page = new QuickPage();
        page.storeId = storeId;
        page.createdAt = now;
        page.update(code, name, position, entries, now);
        return page;
    }

    public void update(String code, String name, int position, List<QuickPageEntry> entries, Instant now) {
        if (position < 0) {
            throw new InvalidCatalogEntryException("A quick page position must be zero or more");
        }
        this.code = CatalogText.required(code, "code");
        this.name = CatalogText.required(name, "name");
        this.position = position;
        this.entries.clear();
        this.entries.addAll(CatalogText.required(entries, "entries"));
        this.updatedAt = now;
    }

    public QuickPageSummary toSummary() {
        return new QuickPageSummary(id, storeId, code, name, position, entries.stream()
                .map(entry -> new QuickPageSummary.Entry(entry.getItemId(), entry.getVariantId(), entry.getMacroId()))
                .toList(), version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }

    public List<QuickPageEntry> getEntries() {
        return List.copyOf(entries);
    }

    public Long getVersion() {
        return version;
    }
}
