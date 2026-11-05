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
import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.production.exception.ProductionRuleViolationException;
import com.xavier.backend.production.model.JobKind;
import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.Printer;
import com.xavier.backend.production.model.PrinterMode;
import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.model.TicketDraft;
import com.xavier.backend.production.model.TicketRouting;
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
 * the job goes to the target, fixed now (D56); NORMAL and HOLD → a QUEUED job (HOLD keeps it there). Work with no
 * printer (no route for its destination, or no printer for its terminal) is kept as an UNROUTED ticket with no job,
 * routable later (D89 (1)); a line with no destination is still not printed (D56).
 */
@Service
public class TicketService {

    private static final String SUBTOTAL = "SUBTOTAL";

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
     * On the terminal's printer, one ticket per subtotal request (D89 (2)): the request id is the ticket's source, so
     * a redelivery is a no-op and a deliberate second subtotal prints. A publication stored by R2 has no request id;
     * its key is the order and a digest of what it prints, as in R2.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void printSubtotal(SubtotalRequested event) {
        List<String> lines = TicketLayouts.subtotal(event.orderNumber(), event.tableCode(), event.lines(),
                event.due());
        TicketDraft draft = event.requestId() != null
                ? subtotalDraft(event, event.requestId(), SUBTOTAL, lines)
                : subtotalDraft(event, event.orderId(),
                        SUBTOTAL + "-" + digest(event.terminalId() + "\n" + String.join("\n", lines)), lines);
        onTerminal(event.storeId(), event.terminalId(), draft);
    }

    private static TicketDraft subtotalDraft(SubtotalRequested event, UUID sourceId, String destination,
                                             List<String> lines) {
        return new TicketDraft(event.storeId(), TicketSource.SUBTOTAL, sourceId, destination, JobKind.ORIGINAL,
                event.orderId(), event.terminalId(), lines, null);
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

    /**
     * A debt payment's non-fiscal slip (D75), on the printer of the terminal it was paid at; one per payment (the
     * payment id is the ticket's source), UNROUTED when that terminal has no printer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void printReceiptSlip(UUID paymentId, UUID storeId, UUID terminalId, List<String> lines) {
        onTerminal(storeId, terminalId, new TicketDraft(storeId, TicketSource.RECEIPT_SLIP, paymentId, "ORIGINAL",
                JobKind.ORIGINAL, null, terminalId, lines, null));
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

    /**
     * D89 (1): resolves the UNROUTED ticket's route now (its destination, or its terminal's printer) and queues its
     * first job, once: the compare-and-set lets one request win, and the job's dedupe key backs it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PrintJob route(ProductionTicket ticket) {
        if (ticket.getRouting() == TicketRouting.ROUTED) {
            throw ProductionConflictException.ticketAlreadyRouted(ticket.getId());
        }
        Printer routed = (ticket.getSourceType().routedByDestination()
                ? routing.printerForDestination(ticket.getStoreId(), ticket.getDestination())
                : printerForTerminal(ticket.getStoreId(), ticket.getTerminalId()))
                .orElseThrow(() -> new ProductionRuleViolationException(ProductionRule.STILL_UNROUTED,
                        "Production ticket " + ticket.getId() + " still has no printer to go to: add its route "
                                + "or its terminal's printer first"));
        Printer target = printing(routed);
        if (tickets.route(ticket.getId(), routed.getId()) == 0) {
            throw ProductionConflictException.ticketAlreadyRouted(ticket.getId());
        }
        return queue(ticket, target.getId(), clock.instant());
    }

    /** Where a new job for work routed to {@code routed} goes now; refused when that printer ignores work. */
    Printer printing(Printer routed) {
        Printer printer = effective(routed);
        if (printer.getMode() == PrinterMode.IGNORE) {
            throw new ProductionRuleViolationException(ProductionRule.PRINTER_IGNORED,
                    "Printer " + printer.getCode() + " is in IGNORE: change its mode to print again");
        }
        return printer;
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
        if (exists(draft)) {
            return;
        }
        routing.printerForDestination(storeId, destination).ifPresentOrElse(printer -> issue(draft, printer),
                () -> keepUnrouted(draft, "destination " + destination));
    }

    private void onTerminal(UUID storeId, UUID terminalId, TicketDraft draft) {
        if (exists(draft)) {
            return;
        }
        printerForTerminal(storeId, terminalId).ifPresentOrElse(printer -> issue(draft, printer),
                () -> keepUnrouted(draft, "terminal " + terminalId));
    }

    /** A terminal mapped in another store must not poison the event: the ticket's printer FK needs this store. */
    Optional<Printer> printerForTerminal(UUID storeId, UUID terminalId) {
        return routing.printerForTerminal(terminalId).filter(candidate -> candidate.getStoreId().equals(storeId));
    }

    private boolean exists(TicketDraft draft) {
        return tickets.existsBySourceTypeAndSourceIdAndDestination(draft.sourceType(), draft.sourceId(),
                draft.destination());
    }

    /** D89 (1): kept with no printer and no job, counted, and announced so the POS and BackOffice see it. */
    private void keepUnrouted(TicketDraft draft, String where) {
        ProductionTicket ticket = save(ProductionTicket.unrouted(draft, clock.instant()));
        metrics.unrouted(draft.storeId(), draft.sourceType(), draft.sourceId(), where);
        notifier.notifyAfterCommit(draft.storeId(), ChangeTopic.PRINT_JOBS, ticket.getId());
    }

    private void issue(TicketDraft draft, Printer routed) {
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
