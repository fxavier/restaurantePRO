package com.xavier.backend.inventory.service;

import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.StockItem;
import com.xavier.backend.inventory.model.dto.ManualMovementLine;
import com.xavier.backend.inventory.model.dto.ManualMovementRequest;
import com.xavier.backend.inventory.model.dto.MovementBatchView;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.repository.StockItemRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual stock movements (D117, D118): waste (OUT), manual adjustment (IN or OUT; an IN needs a unit cost) and
 * return to stock (IN at the average). {@code INVENTORY_MOVE} in the warehouse's store, a reason always, quantities
 * converted to the item's base unit. Outgoing lines are manual for the warehouse's negative policy. Audited.
 */
@Service
public class ManualMovementService {

    public static final String SOURCE_TYPE = "MANUAL_MOVEMENT";

    private final StockLedger ledger;
    private final StockItemRepository items;
    private final Units units;
    private final InventoryScope scope;
    private final BusinessDates businessDates;
    private final AuditLog auditLog;
    private final Clock clock;

    ManualMovementService(StockLedger ledger, StockItemRepository items, Units units, InventoryScope scope,
                          BusinessDates businessDates, AuditLog auditLog, Clock clock) {
        this.ledger = ledger;
        this.items = items;
        this.units = units;
        this.scope = scope;
        this.businessDates = businessDates;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    public MovementBatchView waste(ManualMovementRequest request) {
        return post(request, Kind.WASTE);
    }

    @Transactional
    public MovementBatchView adjust(ManualMovementRequest request) {
        return post(request, Kind.ADJUSTMENT);
    }

    @Transactional
    public MovementBatchView returnToStock(ManualMovementRequest request) {
        return post(request, Kind.RETURN);
    }

    private MovementBatchView post(ManualMovementRequest request, Kind kind) {
        UUID storeId = scope.requireWarehouse(Permission.INVENTORY_MOVE, request.warehouseId());
        if (request.reason() == null || request.reason().isBlank()) {
            throw InventoryRuleException.reasonRequired();
        }
        String reason = request.reason().strip();
        Instant now = clock.instant();
        LocalDate businessDate = businessDates.of(storeId, now);
        UUID sourceId = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        Set<UUID> seen = new HashSet<>();
        List<PostMovement> movements = new ArrayList<>();
        List<Map<String, Object>> audited = new ArrayList<>();
        for (ManualMovementLine line : request.lines()) {
            if (!seen.add(line.stockItemId())) {
                throw InventoryRuleException.duplicateStockItem(line.stockItemId());
            }
            StockItem item = items.findById(line.stockItemId())
                    .orElseThrow(() -> new InventoryNotFoundException(StockItemService.ENTITY, line.stockItemId()));
            BigDecimal quantity = kind.signed(units.toBase(line.quantity(), line.unit(), item.getBaseUnit()));
            PostMovement movement = PostMovement.of(request.warehouseId(), item.getId(), kind.type, quantity,
                    SOURCE_TYPE, sourceId, businessDate, now).manual(reason);
            if (kind == Kind.ADJUSTMENT && quantity.signum() > 0) {
                if (line.unitCost() == null) {
                    throw InventoryRuleException.unitCostRequired(item.getId());
                }
                movement = movement.atCost(line.unitCost());
            }
            movements.add(movement);
            audited.add(Map.of("stockItemId", item.getId().toString(), "quantity", quantity.toPlainString()));
        }
        List<MovementView> posted = ledger.post(movements);
        auditLog.recordAsCurrentActor(kind.action, "StockMovement", sourceId.toString(), storeId, null, reason,
                Map.of("warehouseId", request.warehouseId().toString(), "type", kind.type.name(), "lines", audited));
        return new MovementBatchView(SOURCE_TYPE, sourceId, posted);
    }

    private enum Kind {
        WASTE(MovementType.WASTE, "STOCK_WASTE_RECORDED"),
        ADJUSTMENT(MovementType.MANUAL_ADJUSTMENT, "STOCK_ADJUSTED"),
        RETURN(MovementType.RETURN_TO_STOCK, "STOCK_RETURNED");

        private final MovementType type;
        private final String action;

        Kind(MovementType type, String action) {
            this.type = type;
            this.action = action;
        }

        /** The ledger quantity: waste goes out and a return comes in (both entered positive); an adjustment is signed. */
        BigDecimal signed(BigDecimal quantity) {
            if (this == ADJUSTMENT) {
                if (quantity.signum() == 0) {
                    throw InventoryRuleException.invalidQuantity("An adjustment moves a quantity other than zero.");
                }
                return quantity;
            }
            if (quantity.signum() <= 0) {
                throw InventoryRuleException.invalidQuantity("A " + type + " line moves a positive quantity.");
            }
            return this == WASTE ? quantity.negate() : quantity;
        }
    }
}
