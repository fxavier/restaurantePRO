package com.xavier.backend.organization.controller;

import com.xavier.backend.organization.model.dto.StoreCreateRequest;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.organization.model.dto.StoreUpdateRequest;
import com.xavier.backend.organization.service.StoreService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import com.xavier.backend.shared.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(StoreController.PATH)
class StoreController {

    static final String PATH = "/api/v1/backoffice/stores";

    private final StoreService stores;

    StoreController(StoreService stores) {
        this.stores = stores;
    }

    @GetMapping
    PageResponse<StoreSummary> list(@PageableDefault(sort = "code") Pageable pageable) {
        return stores.list(pageable);
    }

    @GetMapping("/{id}")
    StoreSummary get(@PathVariable UUID id) {
        return stores.get(id);
    }

    @PutMapping("/{id}")
    StoreSummary update(@PathVariable UUID id, @Valid @RequestBody StoreUpdateRequest request) {
        return stores.update(id, request);
    }

    @PostMapping
    ResponseEntity<StoreSummary> create(@Valid @RequestBody StoreCreateRequest request) {
        StoreSummary created = stores.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }
}
