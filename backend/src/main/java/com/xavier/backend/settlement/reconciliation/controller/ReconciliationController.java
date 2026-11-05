package com.xavier.backend.settlement.reconciliation.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.settlement.reconciliation.model.EntryKind;
import com.xavier.backend.settlement.reconciliation.model.dto.BatchView;
import com.xavier.backend.settlement.reconciliation.model.dto.EntryView;
import com.xavier.backend.settlement.reconciliation.model.dto.FetchRequest;
import com.xavier.backend.settlement.reconciliation.model.dto.ImportRequest;
import com.xavier.backend.settlement.reconciliation.model.dto.ResolutionRequest;
import com.xavier.backend.settlement.reconciliation.model.dto.ResolutionView;
import com.xavier.backend.settlement.reconciliation.service.ReconciliationQueryService;
import com.xavier.backend.settlement.reconciliation.service.ResolutionService;
import com.xavier.backend.settlement.reconciliation.service.StatementImportService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Provider statements against payments, payments in doubt, documents against payments (BackOffice, PRD S31). */
@RestController
@RequestMapping(ReconciliationController.PATH)
class ReconciliationController {

    static final String PATH = "/api/v1/backoffice/reconciliation";

    private final StatementImportService imports;
    private final ReconciliationQueryService queries;
    private final ResolutionService resolutions;
    private final IdempotentRequests idempotent;

    ReconciliationController(StatementImportService imports, ReconciliationQueryService queries,
                             ResolutionService resolutions, IdempotentRequests idempotent) {
        this.imports = imports;
        this.queries = queries;
        this.resolutions = resolutions;
        this.idempotent = idempotent;
    }

    /** The canonical CSV (D78); the Idempotency-Key covers the form fields and the file's SHA-256. */
    @PostMapping(path = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<BatchView> importCsv(@RequestHeader("Idempotency-Key") String key,
                                        @RequestPart("file") MultipartFile file, @RequestParam UUID storeId,
                                        @RequestParam String methodCode,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodFrom,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodTo,
                                        HttpServletRequest request) throws IOException {
        byte[] content = file.getBytes();
        ImportRequest body = new ImportRequest(storeId, methodCode, periodFrom, periodTo,
                StatementImportService.sha256(content));
        return idempotent.execute("settlement.reconciliation.import", key, request, body, BatchView.class, () -> {
            BatchView batch = imports.importCsv(body, content);
            return ResponseEntity.created(URI.create(PATH + "/batches/" + batch.id())).body(batch);
        });
    }

    /** D63: the provider is called with no transaction; 201 for a new batch, 200 for one fetched before. */
    @PostMapping("/batches/fetch")
    ResponseEntity<BatchView> fetch(@RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody FetchRequest body, HttpServletRequest request) {
        return idempotent.steps("settlement.reconciliation.fetch", key, request, body, BatchView.class, () -> {
            StatementImportService.Imported imported = imports.fetch(body);
            return ResponseEntity.status(imported.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .location(URI.create(PATH + "/batches/" + imported.batch().id())).body(imported.batch());
        });
    }

    @GetMapping("/batches")
    PageResponse<BatchView> batches(@RequestParam UUID storeId, Pageable pageable) {
        return queries.batches(storeId, pageable);
    }

    @GetMapping("/batches/{id}/entries")
    PageResponse<EntryView> entries(@PathVariable UUID id, @RequestParam(required = false) EntryKind kind,
                                    @RequestParam(required = false) Boolean open, Pageable pageable) {
        return queries.entries(id, kind, open, pageable);
    }

    @PostMapping("/entries/{id}/resolutions")
    ResponseEntity<ResolutionView> resolve(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody ResolutionRequest body, HttpServletRequest request) {
        return idempotent.execute("settlement.reconciliation.resolve", key, request, body, ResolutionView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(resolutions.resolve(id, body)));
    }
}
