package com.xavier.backend.inventory.purchasing.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierReturnRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierReturnUpdateRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierReturnView;
import com.xavier.backend.inventory.purchasing.service.SupplierReturnService;
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

/** Supplier returns (D125), shaped like receipts. Every command but the versioned edit takes an Idempotency-Key. */
@RestController
class SupplierReturnController {

    static final String PATH = PurchasingPaths.BACKOFFICE + "/supplier-returns";

    private final SupplierReturnService returns;
    private final IdempotentRequests idempotent;

    SupplierReturnController(SupplierReturnService returns, IdempotentRequests idempotent) {
        this.returns = returns;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    PageResponse<SupplierReturnView> list(@RequestParam UUID storeId, Pageable pageable) {
        return returns.list(storeId, pageable);
    }

    @PostMapping(PATH)
    ResponseEntity<SupplierReturnView> create(@RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody SupplierReturnRequest body,
                                              HttpServletRequest request) {
        return idempotent.execute("inventory.supplier-return.create", key, request, body, SupplierReturnView.class,
                () -> {
                    SupplierReturnView created = returns.create(body);
                    return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
                });
    }

    @GetMapping(PATH + "/{id}")
    SupplierReturnView get(@PathVariable UUID id) {
        return returns.get(id);
    }

    @PutMapping(PATH + "/{id}")
    SupplierReturnView update(@PathVariable UUID id, @Valid @RequestBody SupplierReturnUpdateRequest body) {
        return returns.update(id, body);
    }

    @PostMapping(PATH + "/{id}/confirm")
    ResponseEntity<SupplierReturnView> confirm(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                               HttpServletRequest request) {
        return idempotent.execute("inventory.supplier-return.confirm", key, request, Map.of(),
                SupplierReturnView.class, () -> ResponseEntity.ok(returns.confirm(id)));
    }

    @PostMapping(PATH + "/{id}/cancel")
    ResponseEntity<SupplierReturnView> cancel(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                              HttpServletRequest request) {
        return idempotent.execute("inventory.supplier-return.cancel", key, request, Map.of(),
                SupplierReturnView.class, () -> ResponseEntity.ok(returns.cancel(id)));
    }
}
