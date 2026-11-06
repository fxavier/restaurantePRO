package com.xavier.backend.inventory.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.model.dto.LevelRequest;
import com.xavier.backend.inventory.model.dto.LevelUpdateRequest;
import com.xavier.backend.inventory.model.dto.LevelView;
import com.xavier.backend.inventory.model.dto.RouteRequest;
import com.xavier.backend.inventory.model.dto.RouteUpdateRequest;
import com.xavier.backend.inventory.model.dto.RouteView;
import com.xavier.backend.inventory.model.dto.WarehouseRequest;
import com.xavier.backend.inventory.model.dto.WarehouseUpdateRequest;
import com.xavier.backend.inventory.model.dto.WarehouseView;
import com.xavier.backend.inventory.service.WarehouseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Warehouses, consumption routes and stock levels at the BackOffice (D114). Creates take an Idempotency-Key. */
@RestController
class WarehouseController {

    static final String WAREHOUSES = InventoryPaths.BACKOFFICE + "/warehouses";
    static final String ROUTES = InventoryPaths.BACKOFFICE + "/consumption-routes";
    static final String LEVELS = InventoryPaths.BACKOFFICE + "/stock-levels";

    private final WarehouseService warehouses;
    private final IdempotentRequests idempotent;

    WarehouseController(WarehouseService warehouses, IdempotentRequests idempotent) {
        this.warehouses = warehouses;
        this.idempotent = idempotent;
    }

    @GetMapping(WAREHOUSES)
    List<WarehouseView> list(@RequestParam UUID storeId) {
        return warehouses.list(storeId);
    }

    @PostMapping(WAREHOUSES)
    ResponseEntity<WarehouseView> create(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody WarehouseRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.warehouse.create", key, request, body, WarehouseView.class, () -> {
            WarehouseView created = warehouses.create(body);
            return ResponseEntity.created(URI.create(WAREHOUSES + "/" + created.id())).body(created);
        });
    }

    @GetMapping(WAREHOUSES + "/{id}")
    WarehouseView get(@PathVariable UUID id) {
        return warehouses.get(id);
    }

    @PutMapping(WAREHOUSES + "/{id}")
    WarehouseView update(@PathVariable UUID id, @Valid @RequestBody WarehouseUpdateRequest body) {
        return warehouses.update(id, body);
    }

    @GetMapping(ROUTES)
    List<RouteView> routes(@RequestParam UUID storeId) {
        return warehouses.routes(storeId);
    }

    @PostMapping(ROUTES)
    ResponseEntity<RouteView> createRoute(@RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody RouteRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.route.create", key, request, body, RouteView.class, () -> {
            RouteView created = warehouses.createRoute(body);
            return ResponseEntity.created(URI.create(ROUTES + "/" + created.id())).body(created);
        });
    }

    @PutMapping(ROUTES + "/{id}")
    RouteView updateRoute(@PathVariable UUID id, @Valid @RequestBody RouteUpdateRequest body) {
        return warehouses.updateRoute(id, body);
    }

    @DeleteMapping(ROUTES + "/{id}")
    ResponseEntity<Void> deleteRoute(@PathVariable UUID id) {
        warehouses.deleteRoute(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(LEVELS)
    List<LevelView> levels(@RequestParam UUID warehouseId) {
        return warehouses.levels(warehouseId);
    }

    @PostMapping(LEVELS)
    ResponseEntity<LevelView> createLevel(@RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody LevelRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.level.create", key, request, body, LevelView.class, () -> {
            LevelView created = warehouses.createLevel(body);
            return ResponseEntity.created(URI.create(LEVELS + "/" + created.warehouseId() + "/"
                    + created.stockItemId())).body(created);
        });
    }

    @PutMapping(LEVELS + "/{warehouseId}/{stockItemId}")
    LevelView updateLevel(@PathVariable UUID warehouseId, @PathVariable UUID stockItemId,
                          @Valid @RequestBody LevelUpdateRequest body) {
        return warehouses.updateLevel(warehouseId, stockItemId, body);
    }

    @DeleteMapping(LEVELS + "/{warehouseId}/{stockItemId}")
    ResponseEntity<Void> deleteLevel(@PathVariable UUID warehouseId, @PathVariable UUID stockItemId) {
        warehouses.deleteLevel(warehouseId, stockItemId);
        return ResponseEntity.noContent().build();
    }
}
