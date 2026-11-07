package com.xavier.backend.promotions.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.promotions.model.dto.PromotionReleaseCreateRequest;
import com.xavier.backend.promotions.model.dto.PromotionReleaseView;
import com.xavier.backend.promotions.service.PromotionReleaseService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Promotion releases of a store (D102); every transition is repeatable with an Idempotency-Key (D24). */
@RestController
@RequestMapping(PromotionReleaseController.PATH)
class PromotionReleaseController {

    static final String PATH = PromotionController.PATH + "/releases";

    private final PromotionReleaseService releases;
    private final IdempotentRequests idempotent;

    PromotionReleaseController(PromotionReleaseService releases, IdempotentRequests idempotent) {
        this.releases = releases;
        this.idempotent = idempotent;
    }

    @GetMapping
    PageResponse<PromotionReleaseView> listForStore(@RequestParam UUID storeId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return releases.listForStore(storeId, pageable);
    }

    @GetMapping("/{id}")
    PromotionReleaseView get(@PathVariable UUID id) {
        return releases.get(id);
    }

    @PostMapping
    ResponseEntity<PromotionReleaseView> create(@RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody PromotionReleaseCreateRequest body,
                                                HttpServletRequest request) {
        return idempotent.execute("promotions.release.create", key, request, body, PromotionReleaseView.class, () -> {
            PromotionReleaseView created = releases.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @PostMapping("/{id}/validate")
    ResponseEntity<PromotionReleaseView> validate(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                                  HttpServletRequest request) {
        return idempotent.execute("promotions.release.validate", key, request, Map.of(), PromotionReleaseView.class,
                () -> ResponseEntity.ok(releases.validate(id)));
    }

    @PostMapping("/{id}/publish")
    ResponseEntity<PromotionReleaseView> publish(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                                 HttpServletRequest request) {
        return idempotent.execute("promotions.release.publish", key, request, Map.of(), PromotionReleaseView.class,
                () -> ResponseEntity.ok(releases.publish(id)));
    }
}
