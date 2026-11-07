package com.xavier.backend.catalog.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.catalog.model.dto.AutomaticItemCreateRequest;
import com.xavier.backend.catalog.model.dto.AutomaticItemSummary;
import com.xavier.backend.catalog.model.dto.AutomaticItemUpdateRequest;
import com.xavier.backend.catalog.service.AutomaticItemService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
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

@RestController
@RequestMapping(AutomaticItemController.PATH)
class AutomaticItemController {

    static final String PATH = "/api/v1/backoffice/catalog/automatic-items";

    private final AutomaticItemService service;
    private final IdempotentRequests idempotency;

    AutomaticItemController(AutomaticItemService service, IdempotentRequests idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping
    List<AutomaticItemSummary> list(@RequestParam(required = false) UUID storeId) {
        return service.list(storeId);
    }

    @GetMapping("/{id}")
    AutomaticItemSummary get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<AutomaticItemSummary> create(@RequestHeader("Idempotency-Key") String key,
                                      @Valid @RequestBody AutomaticItemCreateRequest body, HttpServletRequest request) {
        return idempotency.execute("catalog.automatic-item.create", key, request, body, AutomaticItemSummary.class, () -> {
            AutomaticItemSummary created = service.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @PutMapping("/{id}")
    AutomaticItemSummary update(@PathVariable UUID id, @Valid @RequestBody AutomaticItemUpdateRequest body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
}
