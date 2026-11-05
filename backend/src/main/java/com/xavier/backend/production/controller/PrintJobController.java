package com.xavier.backend.production.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.model.dto.PrintJobView;
import com.xavier.backend.production.service.PrintJobService;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The print queue as people see it, on the POS (the session's store) and in BackOffice: list, retry a FAILED or
 * UNKNOWN job as a new job, acknowledge an UNKNOWN one as printed, and reprint a ticket as a copy (POS).
 */
@RestController
class PrintJobController {

    static final String BACKOFFICE = "/api/v1/backoffice/print-jobs";
    static final String POS = "/api/v1/pos/print-jobs";
    static final String POS_TICKETS = "/api/v1/pos/production-tickets";

    private final PrintJobService jobs;
    private final Idempotent idempotent;

    PrintJobController(PrintJobService jobs, IdempotencyService idempotency, AccessPolicy accessPolicy) {
        this.jobs = jobs;
        this.idempotent = new Idempotent(idempotency, accessPolicy);
    }

    @GetMapping(BACKOFFICE)
    PageResponse<PrintJobView> list(@RequestParam UUID storeId, @RequestParam(required = false) PrintJobStatus status,
                                    @RequestParam(required = false) Instant from,
                                    @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
                                    Pageable pageable) {
        return jobs.listForBackOffice(storeId, status, from, pageable);
    }

    @GetMapping(POS)
    PageResponse<PrintJobView> listForPos(@RequestParam(required = false) PrintJobStatus status,
                                          @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
                                          Pageable pageable) {
        return jobs.listForPos(status, pageable);
    }

    @PostMapping(BACKOFFICE + "/{id}/retry")
    ResponseEntity<PrintJobView> retry(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       HttpServletRequest request) {
        return idempotent.execute("production.job.retry", key, request, null, PrintJobView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(jobs.retryFromBackOffice(id)));
    }

    @PostMapping(POS + "/{id}/retry")
    ResponseEntity<PrintJobView> retryFromPos(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                              HttpServletRequest request) {
        return idempotent.execute("production.job.retry", key, request, null, PrintJobView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(jobs.retryFromPos(id)));
    }

    @PostMapping(BACKOFFICE + "/{id}/acknowledge")
    ResponseEntity<PrintJobView> acknowledge(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                             HttpServletRequest request) {
        return idempotent.execute("production.job.acknowledge", key, request, null, PrintJobView.class,
                () -> ResponseEntity.ok(jobs.acknowledgeFromBackOffice(id)));
    }

    @PostMapping(POS + "/{id}/acknowledge")
    ResponseEntity<PrintJobView> acknowledgeFromPos(@PathVariable UUID id,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    HttpServletRequest request) {
        return idempotent.execute("production.job.acknowledge", key, request, null, PrintJobView.class,
                () -> ResponseEntity.ok(jobs.acknowledgeFromPos(id)));
    }

    /** A COPY job of the ticket, printed with the copy mark and audited (spec §4). */
    @PostMapping(POS_TICKETS + "/{id}/reprints")
    ResponseEntity<PrintJobView> reprint(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                         HttpServletRequest request) {
        return idempotent.execute("production.ticket.reprint", key, request, null, PrintJobView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(jobs.reprint(id)));
    }
}
