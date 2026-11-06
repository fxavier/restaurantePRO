package com.xavier.backend.reporting.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.reporting.model.dto.RebuildRequest;
import com.xavier.backend.reporting.model.dto.RebuildView;
import com.xavier.backend.reporting.service.RebuildService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/backoffice/reports/rebuild} ({@code REPORT_REBUILD} in the store, Idempotency-Key) → 202 with
 * the RUNNING run; its progress is in {@code GET /reports/status}.
 */
@RestController
@RequestMapping("/api/v1/backoffice/reports/rebuild")
class RebuildController {

    private final RebuildService rebuilds;
    private final IdempotentRequests idempotent;

    RebuildController(RebuildService rebuilds, IdempotentRequests idempotent) {
        this.rebuilds = rebuilds;
        this.idempotent = idempotent;
    }

    @PostMapping
    ResponseEntity<RebuildView> rebuild(@RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody RebuildRequest body, HttpServletRequest request) {
        return idempotent.execute("reporting.rebuild", key, request, body, RebuildView.class,
                () -> ResponseEntity.accepted().body(rebuilds.start(body)));
    }
}
