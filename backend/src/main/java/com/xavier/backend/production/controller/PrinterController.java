package com.xavier.backend.production.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.production.model.dto.PrintJobView;
import com.xavier.backend.production.model.dto.PrinterCreateRequest;
import com.xavier.backend.production.model.dto.PrinterModeRequest;
import com.xavier.backend.production.model.dto.PrinterUpdateRequest;
import com.xavier.backend.production.model.dto.PrinterView;
import com.xavier.backend.production.service.PrintJobService;
import com.xavier.backend.production.service.PrinterService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BackOffice printers: configuration, mode and a test page ({@code PRODUCTION_MANAGE} in the store). */
@RestController
@RequestMapping(PrinterController.PATH)
class PrinterController {

    static final String PATH = "/api/v1/backoffice/printers";

    private final PrinterService printers;
    private final PrintJobService jobs;
    private final IdempotentRequests idempotent;

    PrinterController(PrinterService printers, PrintJobService jobs, IdempotentRequests idempotent) {
        this.printers = printers;
        this.jobs = jobs;
        this.idempotent = idempotent;
    }

    @GetMapping
    PageResponse<PrinterView> list(@RequestParam UUID storeId, @PageableDefault(sort = "code") Pageable pageable) {
        return printers.list(storeId, pageable);
    }

    @PostMapping
    ResponseEntity<PrinterView> create(@Valid @RequestBody PrinterCreateRequest body) {
        PrinterView created = printers.create(body);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    PrinterView update(@PathVariable UUID id, @Valid @RequestBody PrinterUpdateRequest body) {
        return printers.update(id, body);
    }

    @PutMapping("/{id}/mode")
    PrinterView changeMode(@PathVariable UUID id, @Valid @RequestBody PrinterModeRequest body) {
        return printers.changeMode(id, body);
    }

    /** A TEST ticket and its job on this printer; {@code Idempotency-Key} required. */
    @PostMapping("/{id}/test")
    ResponseEntity<PrintJobView> test(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                      HttpServletRequest request) {
        return idempotent.execute("production.printer.test", key, request, null, PrintJobView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(jobs.testPrint(id)));
    }
}
