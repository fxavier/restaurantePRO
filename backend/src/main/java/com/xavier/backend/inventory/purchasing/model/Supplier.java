package com.xavier.backend.inventory.purchasing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A supplier (D123): company-wide, with a unique code, tax id and contacts. Deactivated, never deleted. */
@Entity
@Table(schema = "inventory", name = "supplier")
public class Supplier {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    private String taxId;

    private String email;

    private String phone;

    private String notes;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Supplier() {
        // JPA
    }

    public static Supplier create(Contact contact, Instant now) {
        Supplier supplier = new Supplier();
        supplier.createdAt = now;
        supplier.update(contact, true, now);
        return supplier;
    }

    public void update(Contact contact, boolean isActive, Instant now) {
        code = contact.code().strip();
        name = contact.name().strip();
        taxId = blankToNull(contact.taxId());
        email = blankToNull(contact.email());
        phone = blankToNull(contact.phone());
        notes = blankToNull(contact.notes());
        active = isActive;
        updatedAt = now;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** What identifies and reaches a supplier. */
    public record Contact(String code, String name, String taxId, String email, String phone, String notes) {
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

    public String getTaxId() {
        return taxId;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
