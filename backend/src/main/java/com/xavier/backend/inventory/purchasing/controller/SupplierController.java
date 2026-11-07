package com.xavier.backend.inventory.purchasing.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierItemRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierItemView;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierUpdateRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierView;
import com.xavier.backend.inventory.purchasing.service.SupplierService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Suppliers and their items (D123), {@code PURCHASE_MANAGE}. POSTs take an Idempotency-Key. */
@RestController
class SupplierController {

    static final String PATH = PurchasingPaths.BACKOFFICE + "/suppliers";

    private final SupplierService suppliers;
    private final IdempotentRequests idempotent;

    SupplierController(SupplierService suppliers, IdempotentRequests idempotent) {
        this.suppliers = suppliers;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    PageResponse<SupplierView> list(Pageable pageable) {
        return suppliers.list(pageable);
    }

    @PostMapping(PATH)
    ResponseEntity<SupplierView> create(@RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody SupplierRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.supplier.create", key, request, body, SupplierView.class, () -> {
            SupplierView created = suppliers.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @GetMapping(PATH + "/{id}")
    SupplierView get(@PathVariable UUID id) {
        return suppliers.get(id);
    }

    @PutMapping(PATH + "/{id}")
    SupplierView update(@PathVariable UUID id, @Valid @RequestBody SupplierUpdateRequest body) {
        return suppliers.update(id, body);
    }

    @GetMapping(PATH + "/{id}/items")
    List<SupplierItemView> items(@PathVariable UUID id) {
        return suppliers.items(id);
    }

    @PostMapping(PATH + "/{id}/items")
    ResponseEntity<SupplierItemView> addItem(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody SupplierItemRequest body,
                                             HttpServletRequest request) {
        return idempotent.execute("inventory.supplier.item", key, request, body, SupplierItemView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(suppliers.addItem(id, body)));
    }
}
