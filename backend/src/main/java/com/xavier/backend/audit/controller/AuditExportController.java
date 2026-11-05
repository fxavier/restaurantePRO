package com.xavier.backend.audit.controller;

import com.xavier.backend.audit.model.dto.AuditFilter;
import com.xavier.backend.audit.service.AuditExport;
import com.xavier.backend.audit.service.AuditExportService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/backoffice/audit/exports} → {@code text/csv} ({@code AUDIT_EXPORT} + {@code AUDIT_VIEW}, D82).
 * The export is started (authorized, counted, recorded) before the response is touched, so a refusal is still a
 * ProblemDetail. Repeating it is harmless: it reads, and each export is recorded as the person asked for it.
 */
@RestController
@RequestMapping("/api/v1/backoffice/audit/exports")
class AuditExportController {

    private static final String FILENAME = "audit-events.csv";

    private final AuditExportService exports;

    AuditExportController(AuditExportService exports) {
        this.exports = exports;
    }

    @PostMapping
    void export(@Valid @RequestBody AuditFilter filter, HttpServletResponse response) throws IOException {
        AuditExport export = exports.start(filter);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(FILENAME).build().toString());
        exports.writeCsv(export, response.getWriter());
    }
}
