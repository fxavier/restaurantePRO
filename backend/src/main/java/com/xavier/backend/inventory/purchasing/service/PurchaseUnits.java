package com.xavier.backend.inventory.purchasing.service;

import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.model.StockItem;
import com.xavier.backend.inventory.model.StockItemPack;
import com.xavier.backend.inventory.purchasing.exception.PurchasingRuleException;
import com.xavier.backend.inventory.purchasing.model.PurchaseCosts;
import com.xavier.backend.inventory.repository.StockItemPackRepository;
import com.xavier.backend.inventory.repository.StockItemRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The purchase unit of a line (D114, D125): a pack of the stock item, or its base unit without a pack. Quantities and
 * costs given per purchase unit become base units and costs per base unit.
 */
@Service
public class PurchaseUnits {

    private final StockItemRepository items;
    private final StockItemPackRepository packs;

    PurchaseUnits(StockItemRepository items, StockItemPackRepository packs) {
        this.items = items;
        this.packs = packs;
    }

    /**
     * @throws InventoryNotFoundException       the stock item or the pack does not exist (404)
     * @throws PurchasingRuleException PURCHASE_REFERENCE_MISMATCH when the pack is another stock item's
     */
    public Unit of(UUID stockItemId, UUID packId) {
        StockItem item = items.findById(stockItemId)
                .orElseThrow(() -> new InventoryNotFoundException("StockItem", stockItemId));
        if (packId == null) {
            return new Unit(item, null);
        }
        StockItemPack pack = packs.findById(packId)
                .orElseThrow(() -> new InventoryNotFoundException("StockItemPack", packId));
        if (!pack.getStockItemId().equals(stockItemId)) {
            throw PurchasingRuleException.referenceMismatch("Pack " + packId + " is not a pack of stock item "
                    + stockItemId + ".");
        }
        return new Unit(item, pack);
    }

    /** A stock item and its purchase pack ({@code null}: the base unit). */
    public record Unit(StockItem item, StockItemPack pack) {

        public UUID packId() {
            return pack == null ? null : pack.getId();
        }

        public BigDecimal toBase(BigDecimal quantity) {
            return pack == null ? quantity : pack.toBase(quantity);
        }

        public BigDecimal costPerBase(BigDecimal unitCost) {
            return PurchaseCosts.perBaseUnit(unitCost, pack == null ? BigDecimal.ONE : pack.getQuantityBase());
        }
    }
}
