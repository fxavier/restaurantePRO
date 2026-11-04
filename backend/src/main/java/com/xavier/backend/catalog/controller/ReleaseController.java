package com.xavier.backend.catalog.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.catalog.model.dto.PublishRequest;
import com.xavier.backend.catalog.model.dto.ReleaseCreateRequest;
import com.xavier.backend.catalog.model.dto.ReleaseSummary;
import com.xavier.backend.catalog.service.ReleaseService;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ReleaseController.PATH)
class ReleaseController {

    static final String PATH = "/api/v1/backoffice/catalog/releases";

    private final ReleaseService releases;
    private final IdempotencyService idempotency;
    private final AccessPolicy accessPolicy;

    ReleaseController(ReleaseService releases, IdempotencyService idempotency, AccessPolicy accessPolicy) {
        this.releases = releases;
        this.idempotency = idempotency;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping
    List<ReleaseSummary> listForStore(@RequestParam UUID storeId) {
        return releases.listForStore(storeId);
    }

    @GetMapping("/{id}")
    ReleaseSummary get(@PathVariable UUID id) {
        return releases.get(id);
    }

    @PostMapping
    ResponseEntity<ReleaseSummary> create(@Valid @RequestBody ReleaseCreateRequest request) {
        ReleaseSummary created = releases.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PostMapping("/{id}/validate")
    ReleaseSummary validate(@PathVariable UUID id) {
        return releases.validate(id);
    }

    /** Repeatable (D24): the same key and body replay the first response; the key is scoped to the actor. */
    @PostMapping("/{id}/publish")
    ResponseEntity<?> publish(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                              @Valid @RequestBody(required = false) PublishRequest body, HttpServletRequest request) {
        PublishRequest publish = body == null ? PublishRequest.EMPTY : body;
        return idempotency.execute("catalog.release.publish:" + accessPolicy.current().userId(), key,
                request.getMethod() + " " + request.getRequestURI(), publish,
                () -> ResponseEntity.ok(releases.publish(id, publish)));
    }
}
