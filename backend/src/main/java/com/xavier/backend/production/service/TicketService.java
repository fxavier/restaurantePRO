package com.xavier.backend.production.service;

import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.fiscal.model.event.DocumentPrintout;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.orders.OrdersProductionApi;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import com.xavier.backend.orders.model.event.SubtotalRequested;
import com.xavier.backend.production.exception.ProductionConflictException;
import com.xavier.backend.production.model.JobKind;
import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.Printer;
import com.xavier.backend.production.model.PrinterMode;
import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.model.TicketDraft;
import com.xavier.backend.production.model.TicketSource;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.PrinterRepository;
import com.xavier.backend.production.repository.ProductionTicketRepository;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns production work into tickets and jobs, in the caller's transaction. Idempotent per ticket key
 * ({@code source_type, source_id, destination}): work that already has its ticket is skipped, so a redelivered event
 * changes nothing. The routed printer's mode decides: IGNORE → a suppressed ticket, no job, an audit row; REDIRECT →
 * the job goes to the target, fixed now (D56); NORMAL and HOLD → a QUEUED job (HOLD keeps it there).
 */
@Service
public class TicketService {

    private final ProductionTicketRepository tickets;
    private final PrintJobRepository jobs;
    private final PrinterRepository printers;
    private final RoutingService routing;
    private final OrganizationApi organization;
    private final OrdersProductionApi orders;
    private final ProductionMetrics metrics;
    private final AuditLog auditLog;
    private final ChangeNotifier notifier;
    private final Clock clock;

    TicketService(ProductionTicketRepository tickets, PrintJobRepository jobs, PrinterRepository printers,
                  RoutingService routing, OrganizationApi organization, OrdersProductionApi orders,
                  ProductionMetrics metrics, AuditLog auditLog, ChangeNotifier notifier, Clock clock) {
        this.tickets = tickets;
        this.jobs = jobs;
        this.printers = printers;
        this.routing = routing;
        this.organization = organization;
        this.orders = orders;
        this.metrics = metrics;
        this.auditLog = auditLog;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * Production received the round (D35, §8.3): its tickets and jobs, then the batch becomes DISPATCHED. The
     * dispatch is the last step: {@code markBatchDispatched} clears the persistence context.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void receiveBatch(OrderBatchConfirmed event) {
        printBatch(event);
        orders.markBatchDispatched(event.batchId());
    }

    /** A ticket per destination of the round; lines with no destination are not printed (D56). */
    private void printBatch(OrderBatchConfirmed event) {
        Map<String, List<Line>> byDestination = event.lines().stream()
                .filter(line -> line.destination() != null && !line.destination().isBlank())
                .collect(Collectors.groupingBy(line -> line.destination().strip(), LinkedHashMap::new,
                        Collectors.toList()));
        byDestination.forEach((destination, lines) -> routed(event.storeId(), destination, new TicketDraft(
                event.storeId(), TicketSource.BATCH, event.batchId(), destination, JobKind.ORIGINAL, event.orderId(),
                event.terminalId(), TicketLayouts.batch(destination, event.orderNumber(), event.tableCode(),
                event.batchNo(), event.confirmedAt(), zone(event.storeId()), lines), null)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void printVoid(OrderLinesVoided event) {
        String destination = event.line().destination();
        if (destination == null || destination.isBlank()) {
            return;
        }
        routed(event.storeId(), destination.strip(), new TicketDraft(event.storeId(), TicketSource.VOID,
                event.voidId(), destination.strip(), JobKind.ORIGINAL, event.orderId(), event.terminalId(),
                TicketLayouts.voided(destination.strip(), event.orderNumber(), event.tableCode(), event.at(),
                        zone(event.storeId()), event.line(), event.quantity(), event.reason()), null));
    }

    /**
     * On the terminal's printer. {@code SubtotalRequested} carries no request id, so the ticket key is the order and
     * a digest of what it prints: a redelivery is a no-op, and so is a second request for an unchanged bill (that
     * one is a reprint of the ticket).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void printSubtotal(SubtotalRequested event) {
        List<String> lines = TicketLayouts.subtotal(event.orderNumber(), event.tableCode(), event.lines(),
                event.due());
        String destination = "SUBTOTAL-" + digest(event.terminalId() + "\n" + String.join("\n", lines));
        onTerminal(event.storeId(), event.terminalId(), new TicketDraft(event.storeId(), TicketSource.SUBTOTAL,
                event.orderId(), destination, JobKind.ORIGINAL, event.orderId(), event.terminalId(), lines, null));
    }

    /** A document printout (fiscal lays it out and marks a copy), on the printer of the terminal it ran on. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void printDocument(UUID documentId, UUID storeId, UUID terminalId, DocumentPrintout printout,
                              int copyNo) {
        boolean copy = copyNo > 0;
        onTerminal(storeId, terminalId, new TicketDraft(storeId, TicketSource.DOCUMENT, documentId,
                copy ? "COPY-" + copyNo : "ORIGINAL", copy ? JobKind.COPY : JobKind.ORIGINAL, null, terminalId,
                printout.lines(), printout.qrPayload()));
    }

    /** A test page straight to {@code printer}, whatever its mode routes (HOLD still holds it). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Issued printTest(Printer printer) {
        Instant now = clock.instant();
        TicketDraft draft = new TicketDraft(printer.getStoreId(), TicketSource.TEST, UUID.randomUUID(),
                printer.getCode(), JobKind.ORIGINAL, null, null,
                TicketLayouts.test(printer.getCode(), now, zone(printer.getStoreId())), null);
        ProductionTicket ticket = save(ProductionTicket.create(draft, printer.getId(), false, now));
        return new Issued(ticket, queue(ticket, printer.getId(), now));
    }

    /** A ticket and its first job ({@code null} when suppressed). */
    public record Issued(ProductionTicket ticket, PrintJob job) {
    }

    /**
     * The printer that prints for {@code printer} now: itself, or its redirect target (no chains, D56).
     */
    Printer effective(Printer printer) {
        if (printer.getMode() != PrinterMode.REDIRECT) {
            return printer;
        }
        return printers.findById(printer.getRedirectPrinterId()).orElseThrow();
    }

    private void routed(UUID storeId, String destination, TicketDraft draft) {
        Optional<Printer> printer = routing.printerForDestination(storeId, destination);
        if (printer.isEmpty()) {
            metrics.unrouted(storeId, draft.sourceType(), draft.sourceId(), "destination " + destination);
            return;
        }
        issue(draft, printer.get());
    }

    private void onTerminal(UUID storeId, UUID terminalId, TicketDraft draft) {
        Optional<Printer> printer = routing.printerForTerminal(terminalId);
        if (printer.isEmpty()) {
            metrics.unrouted(storeId, draft.sourceType(), draft.sourceId(), "terminal " + terminalId);
            return;
        }
        issue(draft, printer.get());
    }

    private void issue(TicketDraft draft, Printer routed) {
        if (tickets.existsBySourceTypeAndSourceIdAndDestination(draft.sourceType(), draft.sourceId(),
                draft.destination())) {
            return;
        }
        Instant now = clock.instant();
        Printer target = effective(routed);
        boolean suppressed = target.getMode() == PrinterMode.IGNORE;
        ProductionTicket ticket = save(ProductionTicket.create(draft, routed.getId(), suppressed, now));
        if (suppressed) {
            auditLog.recordAsSystem("PRODUCTION_TICKET_SUPPRESSED", "ProductionTicket", ticket.getId().toString(),
                    draft.storeId(), draft.terminalId(), "printer in IGNORE", Map.of("printerId",
                            target.getId().toString(), "sourceType", draft.sourceType().name(), "sourceId",
                            draft.sourceId().toString(), "destination", draft.destination()));
            return;
        }
        queue(ticket, target.getId(), now);
    }

    private PrintJob queue(ProductionTicket ticket, UUID printerId, Instant now) {
        PrintJob job = jobs.saveAndFlush(PrintJob.first(ticket.getId(), ticket.getStoreId(), printerId,
                ticket.getKind(), now));
        notifier.notifyAfterCommit(ticket.getStoreId(), ChangeTopic.PRINT_JOBS, job.getId());
        return job;
    }

    private ProductionTicket save(ProductionTicket ticket) {
        return UniqueConstraints.saveAndFlush(tickets, ticket,
                () -> ProductionConflictException.ticketExists(ticket.getSourceType(), ticket.getSourceId()),
                "production_ticket_source_uk");
    }

    private java.time.ZoneId zone(UUID storeId) {
        return organization.zoneOf(storeId);
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
