package com.xavier.backend.inventory.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.model.dto.ActivationRequest;
import com.xavier.backend.inventory.model.dto.ActivationView;
import com.xavier.backend.inventory.model.dto.ManualMovementRequest;
import com.xavier.backend.inventory.model.dto.MovementBatchView;
import com.xavier.backend.inventory.service.ActivationService;
import com.xavier.backend.inventory.service.ManualMovementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Manual stock movements and go-live (D117, D118, D122); every command takes an Idempotency-Key. */
@RestController
class MovementController {

    static final String MOVEMENTS = InventoryPaths.BACKOFFICE + "/movements";

    private final ManualMovementService movements;
    private final ActivationService activations;
    private final IdempotentRequests idempotent;

    MovementController(ManualMovementService movements, ActivationService activations,
                       IdempotentRequests idempotent) {
        this.movements = movements;
        this.activations = activations;
        this.idempotent = idempotent;
    }

    @PostMapping(MOVEMENTS + "/waste")
    ResponseEntity<MovementBatchView> waste(@RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody ManualMovementRequest body,
                                            HttpServletRequest request) {
        return post("inventory.movement.waste", key, body, request, movements::waste);
    }

    @PostMapping(MOVEMENTS + "/manual-adjustments")
    ResponseEntity<MovementBatchView> adjust(@RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody ManualMovementRequest body,
                                             HttpServletRequest request) {
        return post("inventory.movement.adjust", key, body, request, movements::adjust);
    }

    @PostMapping(MOVEMENTS + "/returns-to-stock")
    ResponseEntity<MovementBatchView> returnToStock(@RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody ManualMovementRequest body,
                                                    HttpServletRequest request) {
        return post("inventory.movement.return", key, body, request, movements::returnToStock);
    }

    @PostMapping(InventoryPaths.BACKOFFICE + "/activations")
    ResponseEntity<ActivationView> activate(@RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody ActivationRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.activation", key, request, body, ActivationView.class,
                () -> ResponseEntity.status(201).body(activations.activate(body)));
    }

    private ResponseEntity<MovementBatchView> post(String operation, String key, ManualMovementRequest body,
                                                   HttpServletRequest request,
                                                   Function<ManualMovementRequest, MovementBatchView> command) {
        return idempotent.execute(operation, key, request, body, MovementBatchView.class,
                () -> ResponseEntity.status(201).body(command.apply(body)));
    }
}
