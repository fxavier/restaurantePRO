package com.xavier.backend.audit.controller;

import com.xavier.backend.audit.model.dto.AuditEventView;
import com.xavier.backend.audit.model.dto.AuditFilter;
import com.xavier.backend.audit.service.AuditQueryService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BackOffice audit reads ({@code AUDIT_VIEW}, scoped by store, D82). */
@RestController
@RequestMapping("/api/v1/backoffice/audit")
class AuditController {

    private final AuditQueryService audit;

    AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping("/events")
    PageResponse<AuditEventView> list(@Valid AuditFilter filter, @PageableDefault(size = 50) Pageable pageable) {
        return audit.list(filter, pageable);
    }

    @GetMapping("/events/{id}")
    AuditEventView get(@PathVariable UUID id) {
        return audit.get(id);
    }

    @GetMapping("/entities/{type}/{id}")
    PageResponse<AuditEventView> history(@PathVariable String type, @PathVariable String id,
                                         @PageableDefault(size = 50) Pageable pageable) {
        return audit.history(type, id, pageable);
    }
}
