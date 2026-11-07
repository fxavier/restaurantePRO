package com.xavier.backend.inventory.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.model.dto.PackRequest;
import com.xavier.backend.inventory.model.dto.PackView;
import com.xavier.backend.inventory.model.dto.StockItemRequest;
import com.xavier.backend.inventory.model.dto.StockItemUpdateRequest;
import com.xavier.backend.inventory.model.dto.StockItemView;
import com.xavier.backend.inventory.service.StockItemService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Stock items and packs at the BackOffice (D114). Creates take an Idempotency-Key; versioned edits do not. */
@RestController
class StockItemController {

    static final String PATH = InventoryPaths.BACKOFFICE + "/stock-items";

    private final StockItemService items;
    private final IdempotentRequests idempotent;

    StockItemController(StockItemService items, IdempotentRequests idempotent) {
        this.items = items;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    PageResponse<StockItemView> list(Pageable pageable) {
        return items.list(pageable);
    }

    @PostMapping(PATH)
    ResponseEntity<StockItemView> create(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody StockItemRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.stock-item.create", key, request, body, StockItemView.class, () -> {
            StockItemView created = items.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @GetMapping(PATH + "/{id}")
    StockItemView get(@PathVariable UUID id) {
        return items.get(id);
    }

    @PutMapping(PATH + "/{id}")
    StockItemView update(@PathVariable UUID id, @Valid @RequestBody StockItemUpdateRequest body) {
        return items.update(id, body);
    }

    @GetMapping(PATH + "/{id}/packs")
    List<PackView> packs(@PathVariable UUID id) {
        return items.packs(id);
    }

    @PostMapping(PATH + "/{id}/packs")
    ResponseEntity<PackView> addPack(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody PackRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.pack.create", key, request, body, PackView.class, () -> {
            PackView created = items.addPack(id, body);
            return ResponseEntity.created(URI.create(PATH + "/" + id + "/packs/" + created.id())).body(created);
        });
    }
}
