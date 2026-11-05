package com.xavier.backend.cash.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.cash.MovementView;
import com.xavier.backend.cash.model.dto.ManualMovementRequest;
import com.xavier.backend.cash.service.CashMovementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PosCashMovementController {

    private final CashMovementService movements;
    private final IdempotentRequests idempotent;

    PosCashMovementController(CashMovementService movements, IdempotentRequests idempotent) {
        this.movements = movements;
        this.idempotent = idempotent;
    }

    @PostMapping("/api/v1/pos/cash/movements")
    ResponseEntity<MovementView> record(@RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody ManualMovementRequest body, HttpServletRequest request) {
        return idempotent.execute("cash.movement.record", key, request, body, MovementView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(movements.record(body)));
    }
}
