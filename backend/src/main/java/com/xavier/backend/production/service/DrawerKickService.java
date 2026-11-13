package com.xavier.backend.production.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.production.exception.ProductionConflictException;
import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.production.exception.ProductionRuleViolationException;
import com.xavier.backend.production.model.JobKind;
import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.Printer;
import com.xavier.backend.production.model.PrinterProfile;
import com.xavier.backend.production.model.Printout;
import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.model.TicketDraft;
import com.xavier.backend.production.model.TicketSource;
import com.xavier.backend.production.model.dto.PrintJobView;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.ProductionTicketRepository;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens a terminal's cash drawer through its receipt printer (D158, ADR 0038): a {@code DRAWER_KICK} ticket and one
 * job on the printer of the terminal, sent by the print worker as {@code ESC p} from the printer's profile, with one
 * attempt ({@link PrintQueue}). Settlement asks for it on a cash payment or refund; a cashier asks for it by hand for
 * a cash movement, with a reason, audited. Idempotent per source: the ticket key
 * {@code (DRAWER_KICK, source, 'DRAWER')} makes a redelivered event kick once.
 */
@Service
public class DrawerKickService {

    static final String DESTINATION = "DRAWER";
    static final String MANUAL = "MANUAL";

    /** ESC p's off time, in 2 ms units: 500 ms, the common value for every drawer. */
    private static final byte OFF_TIME = (byte) 0xFA;

    private static final Logger log = LoggerFactory.getLogger(DrawerKickService.class);

    private final AccessPolicy accessPolicy;
    private final TicketService ticketService;
    private final ProductionTicketRepository tickets;
    private final PrintJobRepository jobs;
    private final AuditLog auditLog;
    private final ChangeNotifier notifier;
    private final Clock clock;

    DrawerKickService(AccessPolicy accessPolicy, TicketService ticketService, ProductionTicketRepository tickets,
                      PrintJobRepository jobs, AuditLog auditLog, ChangeNotifier notifier, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.ticketService = ticketService;
        this.tickets = tickets;
        this.jobs = jobs;
        this.auditLog = auditLog;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * Settlement's request for a cash payment or refund ({@code sourceType} PAYMENT or REFUND). A terminal with no
     * printer, or a printer with no drawer, gets nothing (logged at INFO): a kick is never kept for later. Its 30 s
     * window runs from {@code requestedAt}, the payment's or refund's recording (D158 amended, S60 review), so an
     * event delivered late leaves a FAILED {@code KICK_EXPIRED} job and sends nothing; a publication stored without
     * {@code requestedAt} ({@code null}) is expired at once.
     */
    @Transactional
    public void kick(String sourceType, UUID sourceId, UUID storeId, UUID terminalId, Instant requestedAt) {
        if (tickets.existsBySourceTypeAndSourceIdAndDestination(TicketSource.DRAWER_KICK, sourceId, DESTINATION)) {
            return;
        }
        Optional<Printer> printer = ticketService.printerForTerminal(storeId, terminalId);
        if (printer.isEmpty()) {
            log.info("No drawer kick for {} {}: terminal {} has no receipt printer", sourceType, sourceId,
                    terminalId);
            return;
        }
        if (printer.get().profile().drawerPin() == null) {
            log.info("No drawer kick for {} {}: printer {} has no cash drawer", sourceType, sourceId,
                    printer.get().getCode());
            return;
        }
        issue(sourceType, sourceId, storeId, terminalId, printer.get(), requestedAt);
    }

    /**
     * {@code POST /pos/drawer-kicks}: the session terminal's drawer, for a cash movement (entry, withdrawal, float).
     *
     * @throws ProductionRuleViolationException 422 {@code REASON_REQUIRED}, {@code NO_RECEIPT_PRINTER},
     *                                          {@code NO_CASH_DRAWER}
     */
    @Transactional
    public PrintJobView openManually(String reason) {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_OPERATE);
        if (reason == null || reason.isBlank()) {
            throw new ProductionRuleViolationException(ProductionRule.REASON_REQUIRED,
                    "Opening the drawer by hand needs a reason.");
        }
        Printer printer = ticketService.printerForTerminal(pos.storeId(), pos.terminalId())
                .orElseThrow(() -> new ProductionRuleViolationException(ProductionRule.NO_RECEIPT_PRINTER,
                        "This terminal has no receipt printer, so no cash drawer to open."));
        if (printer.profile().drawerPin() == null) {
            throw new ProductionRuleViolationException(ProductionRule.NO_CASH_DRAWER,
                    "The receipt printer " + printer.getCode() + " has no cash drawer.");
        }
        Kick kick = issue(MANUAL, UUID.randomUUID(), pos.storeId(), pos.terminalId(), printer, clock.instant());
        auditLog.recordAsCurrentActor("DRAWER_OPENED_MANUALLY", "PrintJob", kick.job().getId().toString(),
                pos.storeId(), null, reason.strip(), Map.of("printerId", printer.getId().toString()));
        return kick.job().toView(kick.ticket());
    }

    /**
     * {@code ESC p m t1 t2}, the whole payload of a kick: {@code m} the drawer pin (0 = connector pin 2, 1 = pin 5),
     * {@code t1} the on time in 2 ms units.
     */
    static byte[] payload(PrinterProfile profile) {
        if (profile.drawerPin() == null) {
            throw new IllegalStateException("The printer has no cash drawer");
        }
        return new byte[] {0x1B, 'p', profile.drawerPin().byteValue(), (byte) (profile.drawerPulseMs() / 2),
                OFF_TIME};
    }

    private record Kick(ProductionTicket ticket, PrintJob job) {
    }

    /**
     * The ticket and its job, on the terminal's own printer whatever its mode: the queue fails it if not NORMAL. The
     * job is created as of {@code requestedAt}, which the queue's expiry measures from.
     */
    private Kick issue(String sourceType, UUID sourceId, UUID storeId, UUID terminalId, Printer printer,
                       Instant requestedAt) {
        Instant now = clock.instant();
        TicketDraft draft = new TicketDraft(storeId, TicketSource.DRAWER_KICK, sourceId, DESTINATION,
                JobKind.ORIGINAL, null, terminalId, Printout.kick("DRAWER KICK " + sourceType));
        ProductionTicket ticket = UniqueConstraints.saveAndFlush(tickets,
                ProductionTicket.create(draft, printer.getId(), false, now),
                () -> ProductionConflictException.ticketExists(TicketSource.DRAWER_KICK, sourceId),
                "production_ticket_source_uk");
        PrintJob job = PrintJob.first(ticket.getId(), storeId, printer.getId(), JobKind.ORIGINAL,
                requestedAt != null ? requestedAt : now);
        if (requestedAt == null) {
            job.failUnsent(now, "KICK_EXPIRED");
        }
        job = jobs.saveAndFlush(job);
        notifier.notifyAfterCommit(storeId, ChangeTopic.PRINT_JOBS, job.getId());
        return new Kick(ticket, job);
    }
}
