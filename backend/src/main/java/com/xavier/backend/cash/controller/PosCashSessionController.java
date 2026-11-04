package com.xavier.backend.cash.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.cash.model.dto.OpenSessionRequest;
import com.xavier.backend.cash.model.dto.SessionDetails;
import com.xavier.backend.cash.service.CashSessionService;
import com.xavier.backend.shared.idempotency.IdempotencyService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session close lives in settlement (D37): {@code POST /api/v1/pos/cash/sessions/{id}/close}. */
@RestController
@RequestMapping(PosCashSessionController.PATH)
class PosCashSessionController {

    static final String PATH = "/api/v1/pos/cash/sessions";

    private final CashSessionService sessions;
    private final Idempotent idempotent;

    PosCashSessionController(CashSessionService sessions, IdempotencyService idempotency,
                             AccessPolicy accessPolicy) {
        this.sessions = sessions;
        this.idempotent = new Idempotent(idempotency, accessPolicy);
    }

    @PostMapping
    ResponseEntity<SessionDetails> open(@RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody OpenSessionRequest body, HttpServletRequest request) {
        return idempotent.execute("cash.session.open", key, request, body, SessionDetails.class, () -> {
            SessionDetails opened = sessions.open(body);
            return ResponseEntity.created(URI.create(PATH + "/" + opened.id())).body(opened);
        });
    }

    @GetMapping("/current")
    SessionDetails current() {
        return sessions.current();
    }

    @GetMapping("/{id}")
    SessionDetails get(@PathVariable UUID id) {
        return sessions.get(id);
    }
}
