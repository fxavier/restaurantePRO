package com.xavier.backend.organization.controller;

import com.xavier.backend.organization.model.dto.EnrollmentCodeResponse;
import com.xavier.backend.organization.model.dto.TerminalCreateRequest;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import com.xavier.backend.organization.service.TerminalService;
import com.xavier.backend.shared.web.PageResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(TerminalController.PATH)
class TerminalController {

    static final String PATH = "/api/v1/backoffice/terminals";

    private final TerminalService terminals;

    TerminalController(TerminalService terminals) {
        this.terminals = terminals;
    }

    @GetMapping
    PageResponse<TerminalSummary> list(@RequestParam UUID storeId, @PageableDefault(sort = "code") Pageable pageable) {
        return terminals.list(storeId, pageable);
    }

    /** The plaintext code is in this response only: never cached. */
    @PostMapping("/{id}/enrollment-code")
    ResponseEntity<EnrollmentCodeResponse> issueEnrollmentCode(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(terminals.issueEnrollmentCode(id));
    }

    @PostMapping("/{id}/revoke")
    TerminalSummary revoke(@PathVariable UUID id) {
        return terminals.revoke(id);
    }

    @PostMapping
    ResponseEntity<TerminalSummary> create(@Valid @RequestBody TerminalCreateRequest request) {
        TerminalSummary created = terminals.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }
}
