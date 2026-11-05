package com.xavier.backend.customers.model;

import com.xavier.backend.customers.model.dto.AddressView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** An address of a customer (D72). Edited with its {@code @Version}. */
@Entity
@Table(schema = "customers", name = "customer_address")
public class CustomerAddress {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AddressKind kind;

    @Column(nullable = false, length = 200)
    private String line1;

    @Column(length = 200)
    private String line2;

    @Column(length = 100)
    private String city;

    @Column(length = 20)
    private String postalCode;

    @Column(length = 60)
    private String country;

    private String notes;

    @Version
    private Long version;

    protected CustomerAddress() {
        // JPA
    }

    public static CustomerAddress of(UUID customerId, AddressKind kind, String line1, String line2, String city,
                                     String postalCode, String country, String notes) {
        CustomerAddress address = new CustomerAddress();
        address.customerId = Objects.requireNonNull(customerId, "customerId");
        address.update(kind, line1, line2, city, postalCode, country, notes);
        return address;
    }

    public void update(AddressKind kind, String line1, String line2, String city, String postalCode, String country,
                       String notes) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.line1 = Text.required(line1, "line1");
        this.line2 = Text.optional(line2);
        this.city = Text.optional(city);
        this.postalCode = Text.optional(postalCode);
        this.country = Text.optional(country);
        this.notes = Text.optional(notes);
    }

    public AddressView toView() {
        return new AddressView(id, kind.name(), line1, line2, city, postalCode, country, notes,
                version == null ? 0 : version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public AddressKind getKind() {
        return kind;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
