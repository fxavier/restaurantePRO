package com.xavier.backend.catalog.controller;

import com.xavier.backend.catalog.model.dto.StoreItemExclusionRequest;
import com.xavier.backend.catalog.model.dto.StoreItemExclusionView;
import com.xavier.backend.catalog.service.StoreItemExclusionService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backoffice/catalog/stores/{storeId}/item-exclusions")
class StoreItemExclusionController {

    private final StoreItemExclusionService exclusions;

    StoreItemExclusionController(StoreItemExclusionService exclusions) {
        this.exclusions = exclusions;
    }

    @GetMapping
    PageResponse<StoreItemExclusionView> list(@PathVariable UUID storeId,
            @PageableDefault(sort = "excludedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return exclusions.list(storeId, pageable);
    }

    /** A PUT: repeating it leaves the same state, so no Idempotency-Key. */
    @PutMapping("/{itemId}")
    StoreItemExclusionView exclude(@PathVariable UUID storeId, @PathVariable UUID itemId,
                                   @Valid @RequestBody(required = false) StoreItemExclusionRequest body) {
        return exclusions.exclude(storeId, itemId, body == null ? StoreItemExclusionRequest.EMPTY : body);
    }

    @DeleteMapping("/{itemId}")
    ResponseEntity<Void> include(@PathVariable UUID storeId, @PathVariable UUID itemId) {
        exclusions.include(storeId, itemId);
        return ResponseEntity.noContent().build();
    }
}
