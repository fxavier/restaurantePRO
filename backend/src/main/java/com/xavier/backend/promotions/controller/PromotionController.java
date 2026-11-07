package com.xavier.backend.promotions.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.promotions.model.dto.PromotionCreateRequest;
import com.xavier.backend.promotions.model.dto.PromotionUpdateRequest;
import com.xavier.backend.promotions.model.dto.PromotionView;
import com.xavier.backend.promotions.model.dto.ScopeRequest;
import com.xavier.backend.promotions.model.dto.StoresRequest;
import com.xavier.backend.promotions.model.dto.WindowsRequest;
import com.xavier.backend.promotions.service.PromotionService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The promotions working copy (BackOffice). */
@RestController
@RequestMapping(PromotionController.PATH)
class PromotionController {

    static final String PATH = "/api/v1/backoffice/promotions";
    private static final String ID = "/{id:[0-9a-fA-F-]{36}}";

    private final PromotionService promotions;
    private final IdempotentRequests idempotent;

    PromotionController(PromotionService promotions, IdempotentRequests idempotent) {
        this.promotions = promotions;
        this.idempotent = idempotent;
    }

    @GetMapping
    PageResponse<PromotionView> list(@PageableDefault(sort = "code") Pageable pageable) {
        return promotions.list(pageable);
    }

    @GetMapping(ID)
    PromotionView get(@PathVariable UUID id) {
        return promotions.get(id);
    }

    @PostMapping
    ResponseEntity<PromotionView> create(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody PromotionCreateRequest body, HttpServletRequest request) {
        return idempotent.execute("promotions.promotion.create", key, request, body, PromotionView.class, () -> {
            PromotionView created = promotions.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @PutMapping(ID)
    PromotionView update(@PathVariable UUID id, @Valid @RequestBody PromotionUpdateRequest body) {
        return promotions.update(id, body);
    }

    @DeleteMapping(ID)
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version) {
        promotions.delete(id, version);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(ID + "/scope")
    PromotionView scope(@PathVariable UUID id, @Valid @RequestBody ScopeRequest body) {
        return promotions.replaceScope(id, body);
    }

    @PutMapping(ID + "/windows")
    PromotionView windows(@PathVariable UUID id, @Valid @RequestBody WindowsRequest body) {
        return promotions.replaceWindows(id, body);
    }

    @PutMapping(ID + "/stores")
    PromotionView stores(@PathVariable UUID id, @Valid @RequestBody StoresRequest body) {
        return promotions.replaceStores(id, body);
    }
}
