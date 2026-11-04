package com.xavier.backend.organization.controller;

import com.xavier.backend.organization.model.dto.TerminalEnrollmentRequest;
import com.xavier.backend.organization.model.dto.TerminalEnrollmentResponse;
import com.xavier.backend.organization.service.TerminalService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Device enrollment (D26). Under {@code /api/v1/auth}: the device has no session yet, the code is its credential. */
@RestController
class TerminalEnrollmentController {

    private final TerminalService terminals;

    TerminalEnrollmentController(TerminalService terminals) {
        this.terminals = terminals;
    }

    /** The device token is in this response only: never cached. */
    @PostMapping("/api/v1/auth/terminals/enroll")
    ResponseEntity<TerminalEnrollmentResponse> enroll(@Valid @RequestBody TerminalEnrollmentRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(terminals.enroll(request.code()));
    }
}
