package com.xavier.backend.cash.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.cash.MovementView;
import com.xavier.backend.cash.model.dto.AdjustmentRequest;
import com.xavier.backend.cash.service.CashAdjustmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CashAdjustmentController {

    private final CashAdjustmentService adjustments;
    private final IdempotentRequests idempotent;

    CashAdjustmentController(CashAdjustmentService adjustments, IdempotentRequests idempotent) {
        this.adjustments = adjustments;
        this.idempotent = idempotent;
    }

    @PostMapping("/api/v1/backoffice/cash/sessions/{id}/adjustments")
    ResponseEntity<MovementView> adjust(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody AdjustmentRequest body, HttpServletRequest request) {
        return idempotent.execute("cash.session.adjust", key, request, body, MovementView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(adjustments.adjust(id, body)));
    }
}
