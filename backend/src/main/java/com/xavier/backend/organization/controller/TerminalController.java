package com.xavier.backend.organization.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.organization.model.dto.EnrollmentCodeResponse;
import com.xavier.backend.organization.model.dto.TerminalCreateRequest;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import com.xavier.backend.organization.service.TerminalService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration and revocation take a required {@code Idempotency-Key} (D24, D47): a retry or a step-up resume with
 * the same key replays the stored answer, so the BackOffice can repeat them exactly once. Neither calls Keycloak in
 * the request (the device's client is registered at its enrolment, and disabled after the revocation commits), so
 * both are one-transaction {@code execute} commands.
 */
@RestController
@RequestMapping(TerminalController.PATH)
class TerminalController {

    static final String PATH = "/api/v1/backoffice/terminals";

    private final TerminalService terminals;
    private final IdempotentRequests idempotency;

    TerminalController(TerminalService terminals, IdempotentRequests idempotency) {
        this.terminals = terminals;
        this.idempotency = idempotency;
    }

    @GetMapping
    PageResponse<TerminalSummary> list(@RequestParam UUID storeId, @PageableDefault(sort = "code") Pageable pageable) {
        return terminals.list(storeId, pageable);
    }

    /**
     * The plaintext code is in this response only: never cached, and deliberately <em>no</em> Idempotency-Key, so the
     * code is never stored in {@code shared.idempotency_record} nor replayed (ADR 0035). A repeated issue is harmless: it
     * replaces the previous code (whose pending membership is revoked), so only the latest code shown is valid.
     */
    @PostMapping("/{id}/enrollment-code")
    ResponseEntity<EnrollmentCodeResponse> issueEnrollmentCode(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(terminals.issueEnrollmentCode(id));
    }

    @PostMapping("/{id}/revoke")
    ResponseEntity<TerminalSummary> revoke(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           HttpServletRequest request) {
        return idempotency.execute("organization.terminal.revoke", key, request, null, TerminalSummary.class,
                () -> ResponseEntity.ok(terminals.revoke(id)));
    }

    @PostMapping
    ResponseEntity<TerminalSummary> create(@RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody TerminalCreateRequest body, HttpServletRequest request) {
        return idempotency.execute("organization.terminal.create", key, request, body, TerminalSummary.class, () -> {
            TerminalSummary created = terminals.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }
}
