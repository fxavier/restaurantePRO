package com.xavier.backend.catalog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * An item (or one of its variants) and a quantity, as a menu option or a macro entry names it. The variant belongs to
 * the item (composite foreign key); the quantity is more than zero.
 */
@Embeddable
public class ItemLine {

    @Column(nullable = false)
    private UUID itemId;

    private UUID variantId;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    protected ItemLine() {
        // JPA
    }

    public ItemLine(UUID itemId, UUID variantId, BigDecimal quantity) {
        this.itemId = CatalogText.required(itemId, "itemId");
        this.variantId = variantId;
        this.quantity = CatalogText.required(quantity, "quantity");
    }

    /** Same item and variant, whatever the quantity. */
    public boolean sameKey(ItemLine other) {
        return itemId.equals(other.itemId) && Objects.equals(variantId, other.variantId);
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }
}
