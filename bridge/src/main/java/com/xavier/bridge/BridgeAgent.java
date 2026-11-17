package com.xavier.bridge;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * The bridge's loop (D187): claim one job, send it to its printer, report its outcome, then claim the next (review 1:
 * a job's lease starts only when its own send can start, and one send stays within it, see {@link PrinterSender}).
 * After a job it claims again at once, so a backlog drains at the printers' speed (D50 §7.2 amended); when there was
 * none it waits its poll interval, so the store's network only opens outbound connections and the backend holds no
 * thread for it (review 2). It never retries a send: the backend decides that from the outcome. A job whose outcome
 * cannot be reported stays leased and becomes UNKNOWN at the backend, for a person to resolve, so a ticket is never
 * printed twice by accident.
 */
final class BridgeAgent {

    private static final System.Logger log = System.getLogger(BridgeAgent.class.getName());

    private final BackendClient backend;
    private final PrinterSender printers;
    private final Duration pollInterval;

    BridgeAgent(BackendClient backend, PrinterSender printers, Duration pollInterval) {
        this.backend = backend;
        this.printers = printers;
        this.pollInterval = pollInterval;
    }

    /** One claim and, when a job came, its send and report. @return how long to wait before the next cycle */
    Duration cycle() throws IOException, InterruptedException {
        Optional<ClaimedJob> claimed = backend.claimOne();
        if (claimed.isEmpty()) {
            return pollInterval;
        }
        ClaimedJob job = claimed.get();
        backend.report(job.id(), printers.send(job.host(), job.port(), job.payload(), job.probeStatus()));
        return Duration.ZERO;
    }

    /** Until interrupted; a failed claim or report waits {@code backoff}'s next delay and goes on. */
    void run(Backoff backoff) throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            Duration wait;
            try {
                wait = cycle();
                backoff.reset();
            } catch (IOException | RuntimeException e) {
                wait = backoff.next();
                log.log(System.Logger.Level.WARNING, "Bridge cycle failed, retrying in {0}: {1}", wait,
                        e.toString());
            }
            Thread.sleep(wait);
        }
    }
}
