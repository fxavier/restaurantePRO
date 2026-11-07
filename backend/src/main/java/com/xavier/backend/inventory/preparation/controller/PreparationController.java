package com.xavier.backend.inventory.preparation.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.preparation.model.dto.PreparationRequest;
import com.xavier.backend.inventory.preparation.model.dto.PreparationResult;
import com.xavier.backend.inventory.preparation.model.dto.PreparationView;
import com.xavier.backend.inventory.preparation.service.PreparationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Preparations (D121), {@code INVENTORY_MOVE}; every command takes an Idempotency-Key. */
@RestController
class PreparationController {

    static final String PATH = "/api/v1/backoffice/inventory/preparations";

    private final PreparationService preparations;
    private final IdempotentRequests idempotent;

    PreparationController(PreparationService preparations, IdempotentRequests idempotent) {
        this.preparations = preparations;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH + "/{id}")
    PreparationView get(@PathVariable UUID id) {
        return preparations.get(id).preparation();
    }

    @PostMapping(PATH)
    ResponseEntity<PreparationView> create(@RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody PreparationRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.preparation.create", key, request, body, PreparationView.class, () -> {
            PreparationView created = preparations.create(body).preparation();
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @PostMapping(PATH + "/{id}/confirm")
    ResponseEntity<PreparationResult> confirm(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                              HttpServletRequest request) {
        return idempotent.execute("inventory.preparation.confirm", key, request, id.toString(),
                PreparationResult.class, () -> ResponseEntity.ok(preparations.confirm(id)));
    }

    @PostMapping(PATH + "/{id}/cancel")
    ResponseEntity<PreparationView> cancel(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           HttpServletRequest request) {
        return idempotent.execute("inventory.preparation.cancel", key, request, id.toString(),
                PreparationView.class, () -> ResponseEntity.ok(preparations.cancel(id).preparation()));
    }
}
