package com.xavier.backend.production.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.production.model.dto.DrawerKickRequest;
import com.xavier.backend.production.model.dto.PrintJobView;
import com.xavier.backend.production.service.DrawerKickService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The manual "open drawer" of the POS (D158), for cash entries, withdrawals and floats. */
@RestController
class DrawerKickController {

    static final String POS = "/api/v1/pos/drawer-kicks";

    private final DrawerKickService kicks;
    private final IdempotentRequests idempotent;

    DrawerKickController(DrawerKickService kicks, IdempotentRequests idempotent) {
        this.kicks = kicks;
        this.idempotent = idempotent;
    }

    @PostMapping(POS)
    ResponseEntity<PrintJobView> open(@Valid @RequestBody DrawerKickRequest body,
                                      @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return idempotent.execute("production.drawer.open", key, request, body, PrintJobView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(kicks.openManually(body.reason())));
    }
}
