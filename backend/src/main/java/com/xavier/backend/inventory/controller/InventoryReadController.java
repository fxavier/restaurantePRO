package com.xavier.backend.inventory.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.RebuildRequest;
import com.xavier.backend.inventory.model.dto.RebuildView;
import com.xavier.backend.inventory.model.dto.StatusView;
import com.xavier.backend.inventory.model.dto.StockRow;
import com.xavier.backend.inventory.service.BalanceRebuildService;
import com.xavier.backend.inventory.service.InventoryQueryService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stock, movements, status and the balance rebuild (D113, D116, D118, D122). */
@RestController
class InventoryReadController {

    private final InventoryQueryService queries;
    private final BalanceRebuildService rebuilds;
    private final IdempotentRequests idempotent;

    InventoryReadController(InventoryQueryService queries, BalanceRebuildService rebuilds,
                            IdempotentRequests idempotent) {
        this.queries = queries;
        this.rebuilds = rebuilds;
        this.idempotent = idempotent;
    }

    @GetMapping(InventoryPaths.BACKOFFICE + "/stock")
    PageResponse<StockRow> stock(@RequestParam UUID warehouseId,
                                 @RequestParam(defaultValue = "false") boolean belowMinimum,
                                 @RequestParam(defaultValue = "false") boolean negative, Pageable pageable) {
        return queries.stock(warehouseId, belowMinimum, negative, pageable);
    }

    @GetMapping(InventoryPaths.BACKOFFICE + "/movements")
    PageResponse<MovementView> movements(@RequestParam UUID warehouseId,
                                         @RequestParam(required = false) UUID stockItemId,
                                         @RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to,
                                         @RequestParam(required = false) MovementType type, Pageable pageable) {
        return queries.movements(warehouseId, stockItemId, from, to, type, pageable);
    }

    @GetMapping(InventoryPaths.BACKOFFICE + "/status")
    StatusView status(@RequestParam UUID storeId) {
        return queries.status(storeId);
    }

    @PostMapping(InventoryPaths.BACKOFFICE + "/balances/rebuild")
    ResponseEntity<RebuildView> rebuild(@RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody RebuildRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.balances.rebuild", key, request, body, RebuildView.class,
                () -> ResponseEntity.ok(rebuilds.rebuild(body)));
    }
}
