package com.xavier.backend.production.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.production.exception.PrintJobConflictException;
import com.xavier.backend.production.exception.ProductionNotFoundException;
import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.production.exception.ProductionRuleViolationException;
import com.xavier.backend.production.model.JobKind;
import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.model.Printer;
import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.model.TicketRouting;
import com.xavier.backend.production.model.TicketSource;
import com.xavier.backend.production.model.dto.PrintJobView;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.PrinterRepository;
import com.xavier.backend.production.repository.ProductionTicketRepository;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What people do with the print queue (spec §4 "consulta de falhas, teste e reimpressão auditada"): list it, retry a
 * FAILED or UNKNOWN job as a new job, acknowledge an UNKNOWN job as printed, reprint a ticket as a COPY job, print a
 * test page. An UNROUTED ticket is routed (ProductionTicketService), never reprinted. A reprint creates no document, payment, cash movement or stock movement (§8.4): it is only a job.
 * POS calls take the store from the session (D39); BackOffice calls name it. Resources addressed by id: 404, then
 * 403 (D42).
 */
@Service
public class PrintJobService {

    static final String JOB = "PrintJob";
    static final String TICKET = "ProductionTicket";

    private final PrintJobRepository jobs;
    private final ProductionTicketRepository tickets;
    private final PrinterRepository printers;
    private final TicketService ticketService;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final ChangeNotifier notifier;
    private final Clock clock;

    PrintJobService(PrintJobRepository jobs, ProductionTicketRepository tickets, PrinterRepository printers,
                    TicketService ticketService, AccessPolicy accessPolicy, AuditLog auditLog,
                    ChangeNotifier notifier, Clock clock) {
        this.jobs = jobs;
        this.tickets = tickets;
        this.printers = printers;
        this.ticketService = ticketService;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<PrintJobView> listForBackOffice(UUID storeId, PrintJobStatus status, Instant from,
                                                        Pageable pageable) {
        accessPolicy.require(Permission.PRODUCTION_VIEW, storeId);
        return list(storeId, status, from, pageable);
    }

    @Transactional(readOnly = true)
    public PageResponse<PrintJobView> listForPos(PrintJobStatus status, Pageable pageable) {
        PosContext pos = accessPolicy.requirePos(Permission.PRODUCTION_VIEW);
        return list(pos.storeId(), status, null, pageable);
    }

    @Transactional
    public PrintJobView retryFromBackOffice(UUID jobId) {
        accessPolicy.require(Permission.PRODUCTION_OPERATE, jobStore(jobId));
        return retry(jobId);
    }

    @Transactional
    public PrintJobView retryFromPos(UUID jobId) {
        PosContext pos = accessPolicy.requirePos(Permission.PRODUCTION_OPERATE);
        accessPolicy.requireSessionStore(pos, jobStore(jobId), JOB, jobId);
        return retry(jobId);
    }

    @Transactional
    public PrintJobView acknowledgeFromBackOffice(UUID jobId) {
        accessPolicy.require(Permission.PRODUCTION_OPERATE, jobStore(jobId));
        return acknowledge(jobId);
    }

    @Transactional
    public PrintJobView acknowledgeFromPos(UUID jobId) {
        PosContext pos = accessPolicy.requirePos(Permission.PRODUCTION_OPERATE);
        accessPolicy.requireSessionStore(pos, jobStore(jobId), JOB, jobId);
        return acknowledge(jobId);
    }

    /** A COPY job of a kitchen, bar, void, subtotal or test ticket; a document copy is a fiscal reprint (§8.4). */
    @Transactional
    public PrintJobView reprint(UUID ticketId) {
        PosContext pos = accessPolicy.requirePos(Permission.PRODUCTION_OPERATE);
        UUID storeId = tickets.findStoreIdById(ticketId)
                .orElseThrow(() -> new ProductionNotFoundException(TICKET, ticketId));
        accessPolicy.requireSessionStore(pos, storeId, TICKET, ticketId);
        ProductionTicket ticket = tickets.findById(ticketId).orElseThrow();
        if (ticket.getSourceType() == TicketSource.DOCUMENT) {
            throw new ProductionRuleViolationException(ProductionRule.DOCUMENT_REPRINT_THROUGH_FISCAL,
                    "A document copy is reprinted through the document (fiscal), which numbers its copies");
        }
        if (ticket.getRouting() == TicketRouting.UNROUTED) {
            throw new ProductionRuleViolationException(ProductionRule.STILL_UNROUTED,
                    "Production ticket " + ticketId + " was never printed: route it instead of reprinting it");
        }
        Printer printer = printing(ticket.getPrinterId());
        int copyNo = Math.toIntExact(jobs.countByTicketIdAndKindAndRetryOfIsNull(ticketId, JobKind.COPY)) + 1;
        PrintJob copy = UniqueConstraints.saveAndFlush(jobs,
                PrintJob.copy(ticketId, storeId, printer.getId(), copyNo, clock.instant()),
                () -> PrintJobConflictException.concurrentCopy(ticketId), "print_job_dedupe_key_uk");
        auditLog.recordAsCurrentActor("PRODUCTION_TICKET_REPRINTED", TICKET, ticketId.toString(), storeId, null,
                null, Map.of("printJobId", copy.getId().toString(), "copyNo", copyNo));
        notifier.notifyAfterCommit(storeId, ChangeTopic.PRINT_JOBS, copy.getId());
        return copy.toView(ticket);
    }

    @Transactional
    public PrintJobView testPrint(UUID printerId) {
        UUID storeId = printers.findStoreIdById(printerId)
                .orElseThrow(() -> new ProductionNotFoundException(PrinterService.ENTITY, printerId));
        accessPolicy.require(Permission.PRODUCTION_MANAGE, storeId);
        TicketService.Issued issued = ticketService.printTest(printers.findById(printerId).orElseThrow());
        auditLog.recordAsCurrentActor("PRINTER_TEST_PRINTED", PrinterService.ENTITY, printerId.toString(), storeId,
                null, null, Map.of("printJobId", issued.job().getId().toString()));
        return issued.job().toView(issued.ticket());
    }

    private PrintJobView retry(UUID jobId) {
        PrintJob job = jobs.findById(jobId).orElseThrow();
        Printer printer = printing(job.getPrinterId());
        PrintJob retry = job.retry(printer.getId(), clock.instant());
        jobs.saveAndFlush(job); // a concurrent retry or acknowledge of the same job fails here (409)
        retry = UniqueConstraints.saveAndFlush(jobs, retry, () -> PrintJobConflictException.alreadyResolved(jobId),
                "print_job_dedupe_key_uk", "print_job_retry_of_uk");
        auditLog.recordAsCurrentActor("PRINT_JOB_RETRIED", JOB, jobId.toString(), job.getStoreId(), null, null,
                Map.of("retryJobId", retry.getId().toString(), "status", job.getStatus().name()));
        notifier.notifyAfterCommit(job.getStoreId(), ChangeTopic.PRINT_JOBS, retry.getId());
        return retry.toView(tickets.findById(job.getTicketId()).orElseThrow());
    }

    private PrintJobView acknowledge(UUID jobId) {
        PrintJob job = jobs.findById(jobId).orElseThrow();
        job.acknowledgePrinted();
        jobs.saveAndFlush(job);
        auditLog.recordAsCurrentActor("PRINT_JOB_ACKNOWLEDGED", JOB, jobId.toString(), job.getStoreId(), null, null,
                Map.of("resolution", job.getResolution().name()));
        notifier.notifyAfterCommit(job.getStoreId(), ChangeTopic.PRINT_JOBS, jobId);
        return job.toView(tickets.findById(job.getTicketId()).orElseThrow());
    }

    /** Where a new job for {@code printerId} goes now; refused when that printer ignores work. */
    private Printer printing(UUID printerId) {
        return ticketService.printing(printers.findById(printerId).orElseThrow());
    }

    private UUID jobStore(UUID jobId) {
        return jobs.findStoreIdById(jobId).orElseThrow(() -> new ProductionNotFoundException(JOB, jobId));
    }

    private PageResponse<PrintJobView> list(UUID storeId, PrintJobStatus status, Instant from, Pageable pageable) {
        Specification<PrintJob> spec = (root, query, cb) -> cb.equal(root.get("storeId"), storeId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        Page<PrintJob> page = jobs.findAll(spec, pageable);
        Map<UUID, ProductionTicket> byId = tickets.findAllById(page.map(PrintJob::getTicketId).toSet()).stream()
                .collect(Collectors.toMap(ProductionTicket::getId, Function.identity()));
        return PageResponse.from(page.map(job -> job.toView(byId.get(job.getTicketId()))));
    }
}
