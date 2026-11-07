package com.xavier.backend.inventory.model;

import com.xavier.backend.inventory.model.dto.PackView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A purchase pack of a stock item ("caixa 12 un", "saco 25 kg", D114): {@code quantityBase} base units per pack. */
@Entity
@Table(schema = "inventory", name = "stock_item_pack")
public class StockItemPack {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID stockItemId;

    @Column(nullable = false, updatable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantityBase;

    protected StockItemPack() {
        // JPA
    }

    public static StockItemPack create(UUID stockItemId, String code, String name, BigDecimal quantityBase) {
        if (quantityBase.signum() <= 0) {
            throw new IllegalArgumentException("a pack holds a positive quantity");
        }
        StockItemPack pack = new StockItemPack();
        pack.stockItemId = stockItemId;
        pack.code = code.strip();
        pack.name = name.strip();
        pack.quantityBase = quantityBase.setScale(StockPosition.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        return pack;
    }

    /** {@code packs} of this pack in the item's base unit, HALF_UP at scale 6. */
    public BigDecimal toBase(BigDecimal packs) {
        return packs.multiply(quantityBase).setScale(StockPosition.QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    public PackView toView() {
        return new PackView(id, stockItemId, code, name, quantityBase);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStockItemId() {
        return stockItemId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getQuantityBase() {
        return quantityBase;
    }
}
