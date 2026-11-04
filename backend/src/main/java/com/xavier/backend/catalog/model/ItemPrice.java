package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.dto.ItemPriceSummary;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * The price of an item ({@code variantId} null) or of one of its variants in a {@link PriceList}. Stored as
 * amount + currency columns and exposed as {@link Money} (D15), so the amount always has the currency scale.
 */
@Entity
@Table(schema = "catalog", name = "item_price")
public class ItemPrice {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID priceListId;

    @Column(nullable = false, updatable = false)
    private UUID itemId;

    @Column(updatable = false)
    private UUID variantId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ItemPrice() {
        // JPA
    }

    public static ItemPrice create(UUID priceListId, UUID itemId, UUID variantId, Money price, Instant now) {
        ItemPrice itemPrice = new ItemPrice();
        itemPrice.priceListId = CatalogText.required(priceListId, "priceListId");
        itemPrice.itemId = CatalogText.required(itemId, "itemId");
        itemPrice.variantId = variantId;
        itemPrice.currency = CatalogText.required(price, "price").currency().getCurrencyCode();
        itemPrice.createdAt = now;
        itemPrice.change(price, now);
        return itemPrice;
    }

    /** A new amount in the list currency; the currency itself never changes. */
    public void change(Money price, Instant now) {
        CatalogText.required(price, "price");
        if (!price.currency().getCurrencyCode().equals(currency)) {
            throw new IllegalArgumentException("A price stays in " + currency + ", was " + price.currency());
        }
        if (price.amount().signum() < 0) {
            throw new InvalidCatalogEntryException("A price cannot be negative");
        }
        this.amount = price.amount();
        this.updatedAt = now;
    }

    public Money getPrice() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public ItemPriceSummary toSummary() {
        return new ItemPriceSummary(id, priceListId, itemId, variantId, getPrice(), version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public Long getVersion() {
        return version;
    }
}
