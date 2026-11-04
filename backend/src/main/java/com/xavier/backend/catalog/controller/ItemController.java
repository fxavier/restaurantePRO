package com.xavier.backend.catalog.controller;

import com.xavier.backend.catalog.model.dto.ItemCreateRequest;
import com.xavier.backend.catalog.model.dto.ItemSummary;
import com.xavier.backend.catalog.model.dto.ItemUpdateRequest;
import com.xavier.backend.catalog.model.dto.ItemVariantCreateRequest;
import com.xavier.backend.catalog.model.dto.ItemVariantSummary;
import com.xavier.backend.catalog.model.dto.ItemVariantUpdateRequest;
import com.xavier.backend.catalog.service.ItemService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ItemController.PATH)
class ItemController {

    static final String PATH = "/api/v1/backoffice/catalog/items";

    private final ItemService items;

    ItemController(ItemService items) {
        this.items = items;
    }

    @GetMapping
    PageResponse<ItemSummary> list(@PageableDefault(sort = "code") Pageable pageable) {
        return items.list(pageable);
    }

    @GetMapping("/{id}")
    ItemSummary get(@PathVariable UUID id) {
        return items.get(id);
    }

    @PostMapping
    ResponseEntity<ItemSummary> create(@Valid @RequestBody ItemCreateRequest request) {
        ItemSummary created = items.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    ItemSummary update(@PathVariable UUID id, @Valid @RequestBody ItemUpdateRequest request) {
        return items.update(id, request);
    }

    @GetMapping("/{id}/variants")
    List<ItemVariantSummary> listVariants(@PathVariable UUID id) {
        return items.listVariants(id);
    }

    @GetMapping("/{id}/variants/{variantId}")
    ItemVariantSummary getVariant(@PathVariable UUID id, @PathVariable UUID variantId) {
        return items.getVariant(id, variantId);
    }

    @PostMapping("/{id}/variants")
    ResponseEntity<ItemVariantSummary> createVariant(@PathVariable UUID id,
                                                     @Valid @RequestBody ItemVariantCreateRequest request) {
        ItemVariantSummary created = items.createVariant(id, request);
        return ResponseEntity.created(URI.create(PATH + "/" + id + "/variants/" + created.id())).body(created);
    }

    @PutMapping("/{id}/variants/{variantId}")
    ItemVariantSummary updateVariant(@PathVariable UUID id, @PathVariable UUID variantId,
                                     @Valid @RequestBody ItemVariantUpdateRequest request) {
        return items.updateVariant(id, variantId, request);
    }
}
