package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.model.dto.PriceListSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * The prices of one store (R1: one list per store). Its currency is the company currency when created
 * (single currency, spec §8.4); every {@link ItemPrice} of the list has that currency (composite FK).
 */
@Entity
@Table(schema = "catalog", name = "price_list")
public class PriceList {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PriceList() {
        // JPA
    }

    public static PriceList create(UUID storeId, String name, Currency currency, Instant now) {
        PriceList list = new PriceList();
        list.storeId = CatalogText.required(storeId, "storeId");
        list.currency = CatalogText.required(currency, "currency").getCurrencyCode();
        list.createdAt = now;
        list.rename(name, now);
        return list;
    }

    public void rename(String name, Instant now) {
        this.name = CatalogText.required(name, "name");
        this.updatedAt = now;
    }

    public PriceListSummary toSummary() {
        return new PriceListSummary(id, storeId, name, getCurrency(), version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Currency getCurrency() {
        return Currency.getInstance(currency);
    }

    public Long getVersion() {
        return version;
    }
}
