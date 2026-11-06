package com.xavier.backend.catalog.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.catalog.model.dto.MacroCreateRequest;
import com.xavier.backend.catalog.model.dto.MacroSummary;
import com.xavier.backend.catalog.model.dto.MacroUpdateRequest;
import com.xavier.backend.catalog.service.MacroService;
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
@RequestMapping(MacroController.PATH)
class MacroController {

    static final String PATH = "/api/v1/backoffice/catalog/macros";

    private final MacroService service;
    private final IdempotentRequests idempotency;

    MacroController(MacroService service, IdempotentRequests idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping
    List<MacroSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    MacroSummary get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<MacroSummary> create(@RequestHeader("Idempotency-Key") String key,
                                      @Valid @RequestBody MacroCreateRequest body, HttpServletRequest request) {
        return idempotency.execute("catalog.macro.create", key, request, body, MacroSummary.class, () -> {
            MacroSummary created = service.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @PutMapping("/{id}")
    MacroSummary update(@PathVariable UUID id, @Valid @RequestBody MacroUpdateRequest body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
}
