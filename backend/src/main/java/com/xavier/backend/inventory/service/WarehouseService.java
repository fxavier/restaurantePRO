package com.xavier.backend.inventory.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryConflictException;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.ConsumptionRoute;
import com.xavier.backend.inventory.model.NegativePolicy;
import com.xavier.backend.inventory.model.StockLevel;
import com.xavier.backend.inventory.model.Warehouse;
import com.xavier.backend.inventory.model.dto.LevelRequest;
import com.xavier.backend.inventory.model.dto.LevelUpdateRequest;
import com.xavier.backend.inventory.model.dto.LevelView;
import com.xavier.backend.inventory.model.dto.RouteRequest;
import com.xavier.backend.inventory.model.dto.RouteUpdateRequest;
import com.xavier.backend.inventory.model.dto.RouteView;
import com.xavier.backend.inventory.model.dto.WarehouseRequest;
import com.xavier.backend.inventory.model.dto.WarehouseUpdateRequest;
import com.xavier.backend.inventory.model.dto.WarehouseView;
import com.xavier.backend.inventory.repository.ConsumptionRouteRepository;
import com.xavier.backend.inventory.repository.StockItemRepository;
import com.xavier.backend.inventory.repository.StockLevelRepository;
import com.xavier.backend.inventory.repository.WarehouseRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warehouses, consumption routes and stock levels of a store (D114, D118): {@code INVENTORY_MANAGE} in the store to
 * write, {@code INVENTORY_VIEW} to read. Every write is audited.
 */
@Service
public class WarehouseService {

    static final String ENTITY = "Warehouse";
    static final String ROUTE = "ConsumptionRoute";
    static final String LEVEL = "StockLevel";

    private final WarehouseRepository warehouses;
    private final ConsumptionRouteRepository routes;
    private final StockLevelRepository levels;
    private final StockItemRepository items;
    private final InventoryScope scope;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    WarehouseService(WarehouseRepository warehouses, ConsumptionRouteRepository routes, StockLevelRepository levels,
                     StockItemRepository items, InventoryScope scope, AccessPolicy accessPolicy, AuditLog auditLog,
                     Clock clock) {
        this.warehouses = warehouses;
        this.routes = routes;
        this.levels = levels;
        this.items = items;
        this.scope = scope;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WarehouseView> list(UUID storeId) {
        accessPolicy.require(Permission.INVENTORY_VIEW, storeId);
        return warehouses.findByStoreIdOrderByCode(storeId).stream().map(Warehouse::toView).toList();
    }

    @Transactional(readOnly = true)
    public WarehouseView get(UUID id) {
        scope.requireWarehouse(Permission.INVENTORY_VIEW, id);
        return require(id).toView();
    }

    @Transactional
    public WarehouseView create(WarehouseRequest request) {
        scope.requireStore(Permission.INVENTORY_MANAGE, request.storeId());
        NegativePolicy policy = request.negativePolicy() == null ? NegativePolicy.ALLOW : request.negativePolicy();
        Warehouse warehouse = UniqueConstraints.saveAndFlush(warehouses, Warehouse.create(request.storeId(),
                        request.code(), request.name(), policy, clock.instant()),
                () -> InventoryConflictException.codeTaken("WAREHOUSE", request.code().strip()),
                "warehouse_store_code_uk");
        audit("WAREHOUSE_CREATED", warehouse);
        return warehouse.toView();
    }

    @Transactional
    public WarehouseView update(UUID id, WarehouseUpdateRequest request) {
        scope.requireWarehouse(Permission.INVENTORY_MANAGE, id);
        Warehouse warehouse = require(id);
        OptimisticLocking.requireVersion(Warehouse.class, id, warehouse.getVersion(), request.version());
        warehouse.update(request.code(), request.name(), request.negativePolicy(), request.active(), clock.instant());
        Warehouse saved = UniqueConstraints.saveAndFlush(warehouses, warehouse,
                () -> InventoryConflictException.codeTaken("WAREHOUSE", request.code().strip()),
                "warehouse_store_code_uk");
        audit("WAREHOUSE_UPDATED", saved);
        return saved.toView();
    }

    @Transactional(readOnly = true)
    public List<RouteView> routes(UUID storeId) {
        accessPolicy.require(Permission.INVENTORY_VIEW, storeId);
        return routes.findByStoreIdOrderByDestinationCode(storeId).stream().map(ConsumptionRoute::toView).toList();
    }

    @Transactional
    public RouteView createRoute(RouteRequest request) {
        scope.requireStore(Permission.INVENTORY_MANAGE, request.storeId());
        requireSameStore(request.warehouseId(), request.storeId());
        ConsumptionRoute route = ConsumptionRoute.create(request.storeId(), request.destinationCode(),
                request.warehouseId());
        ConsumptionRoute saved = UniqueConstraints.saveAndFlush(routes, route,
                () -> InventoryConflictException.routeExists(request.storeId(), route.getDestinationCode()),
                "consumption_route_store_destination_uk");
        auditRoute("CONSUMPTION_ROUTE_CREATED", saved);
        return saved.toView();
    }

    @Transactional
    public RouteView updateRoute(UUID id, RouteUpdateRequest request) {
        UUID storeId = routes.storeOf(id).orElseThrow(() -> new InventoryNotFoundException(ROUTE, id));
        accessPolicy.require(Permission.INVENTORY_MANAGE, storeId);
        ConsumptionRoute route = routes.findById(id).orElseThrow(() -> new InventoryNotFoundException(ROUTE, id));
        OptimisticLocking.requireVersion(ConsumptionRoute.class, id, route.getVersion(), request.version());
        requireSameStore(request.warehouseId(), storeId);
        route.routeTo(request.warehouseId());
        ConsumptionRoute saved = routes.saveAndFlush(route);
        auditRoute("CONSUMPTION_ROUTE_UPDATED", saved);
        return saved.toView();
    }

    @Transactional
    public void deleteRoute(UUID id) {
        UUID storeId = routes.storeOf(id).orElseThrow(() -> new InventoryNotFoundException(ROUTE, id));
        accessPolicy.require(Permission.INVENTORY_MANAGE, storeId);
        ConsumptionRoute route = routes.findById(id).orElseThrow(() -> new InventoryNotFoundException(ROUTE, id));
        routes.delete(route);
        auditRoute("CONSUMPTION_ROUTE_DELETED", route);
    }

    @Transactional(readOnly = true)
    public List<LevelView> levels(UUID warehouseId) {
        scope.requireWarehouse(Permission.INVENTORY_VIEW, warehouseId);
        return levels.findByWarehouseIdOrderByStockItemId(warehouseId).stream().map(StockLevel::toView).toList();
    }

    @Transactional
    public LevelView createLevel(LevelRequest request) {
        UUID storeId = scope.requireWarehouse(Permission.INVENTORY_MANAGE, request.warehouseId());
        if (!items.existsById(request.stockItemId())) {
            throw new InventoryNotFoundException(StockItemService.ENTITY, request.stockItemId());
        }
        StockLevel level = UniqueConstraints.saveAndFlush(levels, StockLevel.create(request.warehouseId(),
                        request.stockItemId(), request.minQuantity()),
                () -> InventoryConflictException.levelExists(request.warehouseId(), request.stockItemId()),
                "stock_level_pk");
        auditLevel("STOCK_LEVEL_CREATED", level, storeId);
        return level.toView();
    }

    @Transactional
    public LevelView updateLevel(UUID warehouseId, UUID stockItemId, LevelUpdateRequest request) {
        UUID storeId = scope.requireWarehouse(Permission.INVENTORY_MANAGE, warehouseId);
        StockLevel level = requireLevel(warehouseId, stockItemId);
        OptimisticLocking.requireVersion(StockLevel.class, warehouseId + "/" + stockItemId, level.getVersion(),
                request.version());
        level.change(request.minQuantity());
        StockLevel saved = levels.saveAndFlush(level);
        auditLevel("STOCK_LEVEL_UPDATED", saved, storeId);
        return saved.toView();
    }

    @Transactional
    public void deleteLevel(UUID warehouseId, UUID stockItemId) {
        UUID storeId = scope.requireWarehouse(Permission.INVENTORY_MANAGE, warehouseId);
        StockLevel level = requireLevel(warehouseId, stockItemId);
        levels.delete(level);
        auditLevel("STOCK_LEVEL_DELETED", level, storeId);
    }

    private void requireSameStore(UUID warehouseId, UUID storeId) {
        if (!scope.storeOfWarehouse(warehouseId).equals(storeId)) {
            throw InventoryRuleException.warehouseStoreMismatch(warehouseId, storeId);
        }
    }

    private Warehouse require(UUID id) {
        return warehouses.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
    }

    private StockLevel requireLevel(UUID warehouseId, UUID stockItemId) {
        return levels.findById(new StockLevel.Key(warehouseId, stockItemId))
                .orElseThrow(() -> new InventoryNotFoundException(LEVEL, warehouseId + "/" + stockItemId));
    }

    private void audit(String action, Warehouse warehouse) {
        auditLog.recordAsCurrentActor(action, ENTITY, warehouse.getId().toString(), warehouse.getStoreId(), null,
                null, Map.of("code", warehouse.getCode(), "negativePolicy", warehouse.getNegativePolicy().name(),
                        "active", warehouse.isActive()));
    }

    private void auditRoute(String action, ConsumptionRoute route) {
        Map<String, Object> details = new HashMap<>();
        details.put("destinationCode", route.getDestinationCode() == null ? "" : route.getDestinationCode());
        details.put("warehouseId", route.getWarehouseId().toString());
        auditLog.recordAsCurrentActor(action, ROUTE, route.getId().toString(), route.getStoreId(), null, null,
                details);
    }

    private void auditLevel(String action, StockLevel level, UUID storeId) {
        auditLog.recordAsCurrentActor(action, LEVEL, level.getWarehouseId() + "/" + level.getStockItemId(), storeId,
                null, null, Map.of("minQuantity", level.getMinQuantity().toPlainString()));
    }
}
