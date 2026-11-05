package com.xavier.backend.production.service;

import com.xavier.backend.production.adapter.EscPosRenderer;
import com.xavier.backend.production.adapter.SendOutcome;
import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.model.Printer;
import com.xavier.backend.production.model.PrinterMode;
import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.PrinterRepository;
import com.xavier.backend.production.repository.ProductionTicketRepository;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
 * outcome notifies {@code PRINT_JOBS}.
 */
@Service
public class PrintQueue {

    /** Never an id: stands for "no busy printer" in the claim's NOT IN. */
    private static final UUID NO_PRINTER = new UUID(0, 0);

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
     * Claims at most {@code limit} due jobs: the oldest of each printer, none of {@code busyPrinters}, so each
     * printer gets its tickets one at a time and in order. A job left on a printer that is now in REDIRECT moves to the target
     * instead (it is claimed there on a later run).
     */
    @Transactional
    public List<Dispatch> claimDue(int limit, Set<UUID> busyPrinters) {
        Instant now = clock.instant();
        Set<UUID> excluded = busyPrinters.isEmpty() ? Set.of(NO_PRINTER) : busyPrinters;
        List<PrintJob> due = jobs.lockDue(now, excluded, limit);
        Map<UUID, Printer> printerById = printers.findAllById(due.stream().map(PrintJob::getPrinterId).toList())
                .stream().collect(Collectors.toMap(Printer::getId, Function.identity()));
        List<Dispatch> claimed = new ArrayList<>();
        for (PrintJob job : due) {
            Printer printer = printerById.get(job.getPrinterId());
            if (printer.getMode() == PrinterMode.REDIRECT) {
                job.moveTo(printer.getRedirectPrinterId());
            } else {
                ProductionTicket ticket = tickets.findById(job.getTicketId()).orElseThrow();
                job.claim(now);
                claimed.add(new Dispatch(job.getId(), printer.getId(), printer.address(), renderer.render(
                        ticket.lines(), ticket.getQrPayload(), ticket.printsCopyMarkFor(job.getKind()),
                        printer.getCodePage())));
            }
        }
        jobs.flush();
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

    @Transactional
    public void record(UUID jobId, SendOutcome outcome) {
        PrintJob job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != PrintJobStatus.PROCESSING) {
            log.warn("Print job {} is {} already; its {} outcome is not recorded", jobId, job.getStatus(), outcome);
            return;
        }
        Instant now = clock.instant();
        switch (outcome) {
            case ACCEPTED -> job.recordSent(now);
            case NOT_CONNECTED -> job.recordNotConnected(now);
            case INTERRUPTED_AFTER_WRITE -> job.recordInterrupted();
        }
        jobs.saveAndFlush(job);
        if (job.getStatus() == PrintJobStatus.FAILED || job.getStatus() == PrintJobStatus.UNKNOWN) {
            log.warn("Print job {} is {} after {} attempt(s)", jobId, job.getStatus(), job.getAttempts());
        }
        notifier.notifyAfterCommit(job.getStoreId(), ChangeTopic.PRINT_JOBS, jobId);
    }
}
