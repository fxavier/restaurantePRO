package com.xavier.backend.inventory.model;

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

/** A warehouse of one store (§8.1, D114), with its negative-stock policy (D118). Its store never changes. */
@Entity
@Table(schema = "inventory", name = "warehouse")
public class Warehouse {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private NegativePolicy negativePolicy;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Warehouse() {
        // JPA
    }

    public static Warehouse create(UUID storeId, String code, String name, NegativePolicy policy, Instant now) {
        Warehouse warehouse = new Warehouse();
        warehouse.storeId = storeId;
        warehouse.createdAt = now;
        warehouse.update(code, name, policy, true, now);
        return warehouse;
    }

    public void update(String code, String name, NegativePolicy policy, boolean active, Instant now) {
        this.code = code.strip();
        this.name = name.strip();
        this.negativePolicy = policy;
        this.active = active;
        this.updatedAt = now;
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

    public NegativePolicy getNegativePolicy() {
        return negativePolicy;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
