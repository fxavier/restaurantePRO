package com.xavier.backend.inventory.purchasing.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.purchasing.model.dto.PurchaseOrderRequest;
import com.xavier.backend.inventory.purchasing.model.dto.PurchaseOrderUpdateRequest;
import com.xavier.backend.inventory.purchasing.model.dto.PurchaseOrderView;
import com.xavier.backend.inventory.purchasing.service.PurchaseOrderService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Purchase orders (D124). Every command but the versioned edit takes an Idempotency-Key. */
@RestController
class PurchaseOrderController {

    static final String PATH = PurchasingPaths.BACKOFFICE + "/purchase-orders";

    private final PurchaseOrderService orders;
    private final IdempotentRequests idempotent;

    PurchaseOrderController(PurchaseOrderService orders, IdempotentRequests idempotent) {
        this.orders = orders;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    PageResponse<PurchaseOrderView> list(@RequestParam UUID storeId, Pageable pageable) {
        return orders.list(storeId, pageable);
    }

    @PostMapping(PATH)
    ResponseEntity<PurchaseOrderView> create(@RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody PurchaseOrderRequest body,
                                             HttpServletRequest request) {
        return idempotent.execute("inventory.purchase-order.create", key, request, body, PurchaseOrderView.class,
                () -> {
                    PurchaseOrderView created = orders.create(body);
                    return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
                });
    }

    @GetMapping(PATH + "/{id}")
    PurchaseOrderView get(@PathVariable UUID id) {
        return orders.get(id);
    }

    @PutMapping(PATH + "/{id}")
    PurchaseOrderView update(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderUpdateRequest body) {
        return orders.update(id, body);
    }

    @PostMapping(PATH + "/{id}/submit")
    ResponseEntity<PurchaseOrderView> submit(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                             HttpServletRequest request) {
        return idempotent.execute("inventory.purchase-order.submit", key, request, Map.of(),
                PurchaseOrderView.class, () -> ResponseEntity.ok(orders.submit(id)));
    }

    @PostMapping(PATH + "/{id}/close")
    ResponseEntity<PurchaseOrderView> close(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                            HttpServletRequest request) {
        return idempotent.execute("inventory.purchase-order.close", key, request, Map.of(),
                PurchaseOrderView.class, () -> ResponseEntity.ok(orders.close(id)));
    }

    @PostMapping(PATH + "/{id}/cancel")
    ResponseEntity<PurchaseOrderView> cancel(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                             HttpServletRequest request) {
        return idempotent.execute("inventory.purchase-order.cancel", key, request, Map.of(),
                PurchaseOrderView.class, () -> ResponseEntity.ok(orders.cancel(id)));
    }
}
