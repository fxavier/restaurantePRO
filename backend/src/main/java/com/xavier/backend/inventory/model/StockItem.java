package com.xavier.backend.inventory.model;

import com.xavier.backend.inventory.model.dto.StockItemView;
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
 * A stock item (D114), company-wide: code, name, base unit and kind. The base unit never changes once created,
 * because every ledger quantity of the item is in it; it is deactivated, never deleted.
 */
@Entity
@Table(schema = "inventory", name = "stock_item")
public class StockItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, updatable = false, length = 10)
    private String baseUnit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private StockItemKind kind;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected StockItem() {
        // JPA
    }

    public static StockItem create(String code, String name, StockUnit baseUnit, StockItemKind kind, Instant now) {
        StockItem item = new StockItem();
        item.baseUnit = baseUnit.getCode();
        item.kind = kind;
        item.createdAt = now;
        item.update(code, name, true, now);
        return item;
    }

    public void update(String code, String name, boolean active, Instant now) {
        this.code = code.strip();
        this.name = name.strip();
        this.active = active;
        this.updatedAt = now;
    }

    public StockItemView toView() {
        return new StockItemView(id, code, name, baseUnit, kind.name(), active, getVersion());
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

    public String getBaseUnit() {
        return baseUnit;
    }

    public StockItemKind getKind() {
        return kind;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
