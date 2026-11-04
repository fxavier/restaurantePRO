package com.xavier.backend.cash.model;

import com.xavier.backend.cash.model.dto.RegisterSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A cash drawer of a store (CONTEXT.md CashRegister). Never deleted: sessions, movements and closings refer to it,
 * so it is deactivated instead.
 */
@Entity
@Table(schema = "cash", name = "cash_register")
public class CashRegister {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CashRegister() {
        // JPA
    }

    public static CashRegister create(UUID storeId, String code, String name, Instant now) {
        CashRegister register = new CashRegister();
        register.storeId = Objects.requireNonNull(storeId, "storeId");
        register.createdAt = now;
        register.update(code, name, true, now);
        return register;
    }

    public void update(String code, String name, boolean active, Instant now) {
        this.code = code.strip();
        this.name = name.strip();
        this.active = active;
        this.updatedAt = now;
    }

    public RegisterSummary toSummary() {
        return new RegisterSummary(id, storeId, code, name, active, version);
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

    public boolean isActive() {
        return active;
    }

    public Long getVersion() {
        return version;
    }
}
