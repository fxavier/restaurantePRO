package com.xavier.backend.identity.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.identity.model.dto.SupportGrantRequest;
import com.xavier.backend.identity.model.dto.SupportGrantSummary;
import com.xavier.backend.identity.service.SupportGrantService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Support grants (D83): {@code SUPPORT_ACCESS_MANAGE}, installation-wide; grant and revoke take an Idempotency-Key. */
@RestController
class SupportGrantController {

    static final String PATH = "/api/v1/backoffice/support-grants";

    private final SupportGrantService grants;
    private final IdempotentRequests idempotent;

    SupportGrantController(SupportGrantService grants, IdempotentRequests idempotent) {
        this.grants = grants;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    PageResponse<SupportGrantSummary> list(@RequestParam(required = false) UUID accountId, Pageable pageable) {
        return grants.list(accountId, pageable);
    }

    @PostMapping(PATH)
    ResponseEntity<SupportGrantSummary> grant(@RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody SupportGrantRequest body,
                                              HttpServletRequest request) {
        return idempotent.execute("identity.support-grant.grant", key, request, body, SupportGrantSummary.class, () -> {
            SupportGrantSummary created = grants.grant(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @PostMapping(PATH + "/{id}/revoke")
    ResponseEntity<SupportGrantSummary> revoke(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                               HttpServletRequest request) {
        return idempotent.execute("identity.support-grant.revoke", key, request, null, SupportGrantSummary.class,
                () -> ResponseEntity.ok(grants.revoke(id)));
    }
}
