package com.xavier.backend.production.service;

import com.xavier.backend.production.adapter.EscPosRenderer;
import com.xavier.backend.production.adapter.SendOutcome;
import com.xavier.backend.production.adapter.SendResult;
import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.model.Printer;
import com.xavier.backend.production.model.PrinterMode;
import com.xavier.backend.production.model.PrinterProfile;
import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.model.TicketSource;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.PrinterRepository;
import com.xavier.backend.production.repository.ProductionTicketRepository;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of the print worker (D50 §7.2): tx1 claims due jobs ({@code FOR UPDATE SKIP LOCKED}, so
 * overlapping runs never share one) and renders them; the send happens elsewhere with no transaction; tx2 records
 * the outcome, only while the job is still PROCESSING (its version decides against a concurrent lease expiry). Every
 * outcome notifies {@code PRINT_JOBS}. A drawer kick (D158) gets one attempt: never held, redirected, queued behind
 * other work or retried, and not sent once it is older than {@link PrintJob#KICK_TTL}.
 */
@Service
public class PrintQueue {

    private static final Logger log = LoggerFactory.getLogger(PrintQueue.class);

    private final PrintJobRepository jobs;
    private final PrinterRepository printers;
    private final ProductionTicketRepository tickets;
    private final EscPosRenderer renderer;
    private final ChangeNotifier notifier;
    private final Clock clock;

    PrintQueue(PrintJobRepository jobs, PrinterRepository printers, ProductionTicketRepository tickets,
               EscPosRenderer renderer, ChangeNotifier notifier, Clock clock) {
        this.jobs = jobs;
        this.printers = printers;
        this.tickets = tickets;
        this.renderer = renderer;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * Claims at most {@code limit} due jobs: the drawer kicks first, then each printer's head job, if due, and none
     * for a printer with a send in flight, so each printer gets its tickets one at a time and in order. A job left on
     * a printer that is now in REDIRECT moves to the target instead (it is claimed there on a later run).
     */
    @Transactional
    public List<Dispatch> claimDue(int limit) {
        Instant now = clock.instant();
        List<Dispatch> claimed = claimKicks(now, limit);
        // lockDue is a native query, so Hibernate flushes the kicks claimed above first: their printers are PROCESSING
        // and send nothing else this run
        List<PrintJob> due = jobs.lockDue(now, limit - claimed.size());
        Map<UUID, Printer> printerById = printers.findAllById(due.stream().map(PrintJob::getPrinterId).toList())
                .stream().collect(Collectors.toMap(Printer::getId, Function.identity()));
        for (PrintJob job : due) {
            Printer printer = printerById.get(job.getPrinterId());
            if (printer.getMode() == PrinterMode.REDIRECT) {
                job.moveTo(printer.getRedirectPrinterId());
            } else {
                ProductionTicket ticket = tickets.findById(job.getTicketId()).orElseThrow();
                job.claim(now);
                PrinterProfile profile = printer.profile();
                claimed.add(new Dispatch(job.getId(), printer.getId(), printer.address(), renderer.render(
                        ticket.printout(), profile, printer.getCodePage(), ticket.printsCopyMarkFor(job.getKind())),
                        profile.statusProbe()));
            }
        }
        jobs.flush();
        return claimed;
    }

    /**
     * D158: a kick on a printer that is not NORMAL and active, older than {@link PrintJob#KICK_TTL}, or on a printer
     * whose drawer was removed since, fails unsent; the others go out, one per printer per run, without a status
     * probe (an open cover or paper out does not stop a drawer).
     */
    private List<Dispatch> claimKicks(Instant now, int limit) {
        List<Dispatch> claimed = new ArrayList<>();
        Set<UUID> kicking = new HashSet<>();
        for (PrintJob kick : jobs.lockDueKicks(limit)) {
            Printer printer = printers.findById(kick.getPrinterId()).orElseThrow();
            String refusal = printer.getMode() != PrinterMode.NORMAL || !printer.isActive() ? "PRINTER_NOT_NORMAL"
                    : kick.kickExpired(now) ? "KICK_EXPIRED"
                    : printer.profile().drawerPin() == null ? "NO_CASH_DRAWER"
                    : null;
            if (refusal != null) {
                kick.failUnsent(now, refusal);
                log.warn("Drawer kick {} failed unsent: {}", kick.getId(), refusal);
                notifier.notifyAfterCommit(kick.getStoreId(), ChangeTopic.PRINT_JOBS, kick.getId());
            } else if (kicking.add(printer.getId())) {
                kick.claim(now);
                claimed.add(new Dispatch(kick.getId(), printer.getId(), printer.address(),
                        DrawerKickService.payload(printer.profile()), false));
            }
        }
        return claimed;
    }

    /** PROCESSING past the lease: a crash during the send. UNKNOWN, for a person to acknowledge or retry (§4). */
    @Transactional
    public int expireLeases() {
        List<PrintJob> lost = jobs.lockExpiredLeases(clock.instant().minus(PrintJob.LEASE));
        for (PrintJob job : lost) {
            job.expireLease();
            log.warn("Print job {} lost its lease while sending: UNKNOWN", job.getId());
            notifier.notifyAfterCommit(job.getStoreId(), ChangeTopic.PRINT_JOBS, job.getId());
        }
        jobs.flush();
        return lost.size();
    }

    /**
     * The send's outcome, and what it learnt about the printer ({@code last_status}, D157; written without moving the
     * printer's version, so it never conflicts with a BackOffice edit). A send that wrote nothing (refused, or the
     * printer not ready) waits and retries; an interrupted one is UNKNOWN.
     */
    @Transactional
    public void record(UUID jobId, SendResult result) {
        PrintJob job = jobs.findById(jobId).orElseThrow();
        Instant now = clock.instant();
        printers.recordStatus(job.getPrinterId(), result.printerStatus(), now);
        SendOutcome outcome = result.outcome();
        if (job.getStatus() != PrintJobStatus.PROCESSING) {
            log.warn("Print job {} is {} already; its {} outcome is not recorded", jobId, job.getStatus(), outcome);
            return;
        }
        if (outcome == SendOutcome.ACCEPTED) {
            job.recordSent(now);
        } else if (outcome.nothingWritten() && isKick(job)) {
            job.failUnsent(now, outcome.printerStatus());
        } else if (outcome.nothingWritten()) {
            job.recordNothingWritten(now, outcome.printerStatus());
        } else {
            job.recordInterrupted();
        }
        jobs.saveAndFlush(job);
        if (job.getStatus() == PrintJobStatus.FAILED || job.getStatus() == PrintJobStatus.UNKNOWN) {
            log.warn("Print job {} is {} after {} attempt(s)", jobId, job.getStatus(), job.getAttempts());
        }
        notifier.notifyAfterCommit(job.getStoreId(), ChangeTopic.PRINT_JOBS, jobId);
    }

    private boolean isKick(PrintJob job) {
        return tickets.findById(job.getTicketId()).orElseThrow().getSourceType() == TicketSource.DRAWER_KICK;
    }
}
