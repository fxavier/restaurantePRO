package com.xavier.bridge;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * The bridge's loop (D187): claim the store's jobs, send each to its printer, report each outcome. After a batch it
 * claims again at once (a printer's backlog drains at its own speed, D50 §7.2 amended); when there was nothing it waits
 * on the backend (a long poll), so the store's network only ever opens outbound connections. It never retries a send:
 * the backend decides that from the outcome. A job whose outcome cannot be reported stays leased and becomes UNKNOWN
 * at the backend, for a person to resolve, so a ticket is never printed twice by accident.
 */
final class BridgeAgent {

    private static final System.Logger log = System.getLogger(BridgeAgent.class.getName());

    private final BackendClient backend;
    private final PrinterSender printers;
    private final Duration idleWait;
    private Duration nextWait;

    BridgeAgent(BackendClient backend, PrinterSender printers, Duration idleWait) {
        this.backend = backend;
        this.printers = printers;
        this.idleWait = idleWait;
        this.nextWait = idleWait;
    }

    /** One claim and its sends. @return how many jobs were sent (whatever their outcome) */
    int cycle() throws IOException, InterruptedException {
        List<ClaimedJob> jobs = backend.claim(nextWait);
        for (ClaimedJob job : jobs) {
            SendResult result = printers.send(job.host(), job.port(), job.payload(), job.probeStatus());
            backend.report(job.id(), result);
        }
        nextWait = jobs.isEmpty() ? idleWait : Duration.ZERO;
        return jobs.size();
    }

    /** Until interrupted; a failed claim or report waits {@code backoff} and goes on. */
    void run(Duration backoff) throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                cycle();
            } catch (IOException | RuntimeException e) {
                log.log(System.Logger.Level.WARNING, "Bridge cycle failed, retrying in {0}: {1}", backoff,
                        e.toString());
                Thread.sleep(backoff);
            }
        }
    }
}
