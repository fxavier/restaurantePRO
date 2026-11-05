package com.xavier.backend.reporting.controller;

import com.xavier.backend.reporting.model.dto.ExportRequest;
import com.xavier.backend.reporting.service.ReportExportService;
import com.xavier.backend.reporting.service.ReportFile;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/backoffice/reports/exports {report, params}} → {@code text/csv} ({@code REPORT_EXPORT} and
 * {@code REPORT_VIEW} in the store, audited). Repeating it is harmless: it reads, and each export is recorded.
 */
@RestController
@RequestMapping("/api/v1/backoffice/reports/exports")
class ReportExportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportExportService exports;

    ReportExportController(ReportExportService exports) {
        this.exports = exports;
    }

    @PostMapping
    ResponseEntity<String> export(@Valid @RequestBody ExportRequest request) {
        ReportFile file = exports.export(request);
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.csv());
    }
}
