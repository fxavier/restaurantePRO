package com.xavier.backend.production.service;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.production.adapter.PrinterTransport;
import com.xavier.backend.production.adapter.SendResult;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * <b>Test only.</b> Every store's print bridge (D187), in the test JVM: what the central backend no longer does. The
 * backend suites keep printing to {@code TcpFakePrinter}s as before S73, so a test can watch a ticket reach a printer;
 * the real agent ({@code bridge/}) claims over HTTP ({@code BridgeJobService}, {@code BridgeJobClaimTest}). This one
 * claims through the same {@link PrintQueue} for every store of every tenant ({@code PRINT_WORKER}) and records the same
 * outcomes. Off with {@code restaurantpro.test.print-bridge=false} (the suites that are the bridge themselves).
 *
 * <p>The R5 print worker (D50 §7.2, D46), as it was: each run, on the scheduler thread, expires lost leases and claims due jobs (short
 * transactions), then hands each send to a dedicated bounded pool: the shared scheduler thread never waits on a
 * printer, and no send holds a transaction or a connection (D48; the transport checks). A run claims no more than
 * the pool has free threads, at most one job per printer, and none for a printer with a send in flight on any worker
 * (its job is PROCESSING in the database). When a send is recorded its sender claims again, so a printer's backlog drains at its own speed rather than one job per poll.
 */
@Component
@ConditionalOnProperty(name = "restaurantpro.test.print-bridge", havingValue = "true", matchIfMissing = true)
public class InProcessPrintBridge implements DisposableBean {

    static final int MAX_CLAIM = 10;

    /**
     * One lock for every stand-in of the JVM (every cached context has one): a pause taken under it means no stand-in
     * is claiming now and none starts until it ends.
     */
    private static final Object CLAIMS = new Object();
    private static int pauses;

    private static final Logger log = LoggerFactory.getLogger(InProcessPrintBridge.class);

    private final PrintQueue queue;
    private final PrinterTransport transport;
    private final StoreScopes scopes;
    private final Semaphore freeSenders;
    private final ThreadPoolExecutor senders;

    InProcessPrintBridge(PrintQueue queue, PrinterTransport transport, StoreScopes scopes,
                @Value("${restaurantpro.production.worker.senders:4}") int senders) {
        this.queue = queue;
        this.transport = transport;
        this.scopes = scopes;
        this.freeSenders = new Semaphore(senders);
        this.senders = new ThreadPoolExecutor(senders, senders, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(senders), Thread.ofPlatform().name("print-sender-", 0).daemon().factory());
    }

    /** One run; also what tests call. */
    @SystemScoped(SystemScope.PRINT_WORKER)
    @Scheduled(fixedDelayString = "${restaurantpro.production.worker.poll:PT1S}")
    public int poll() {
        queue.expireLeases();
        return claimAndSend();
    }

    /**
     * Synchronized: one claimer per JVM, so the free-sender count stays exact, and none after {@link #destroy()}
     * shut the pool (a job claimed then would never be sent).
     */
    private synchronized int claimAndSend() {
        int free = freeSenders.availablePermits();
        if (free == 0 || senders.isShutdown()) {
            return 0;
        }
        List<Dispatch> claimed;
        synchronized (CLAIMS) {
            if (pauses > 0) {
                return 0;
            }
            claimed = queue.claimDue(Math.min(free, MAX_CLAIM));
        }
        for (Dispatch dispatch : claimed) {
            freeSenders.acquireUninterruptibly();
            // the sender records its send in the claimer's scope (PRINT_WORKER), on the sender thread (D166)
            senders.execute(scopes.propagate(() -> deliver(dispatch)));
        }
        return claimed.size();
    }

    private void deliver(Dispatch dispatch) {
        try {
            SendResult result = transport.send(dispatch.address(), dispatch.payload(), dispatch.probeStatus());
            queue.record(dispatch.jobId(), result);
        } catch (RuntimeException e) {
            // the job stays PROCESSING; its lease turns it UNKNOWN for a person to resolve
            log.error("Print job {} was not recorded after its send", dispatch.jobId(), e);
        } finally {
            freeSenders.release();
        }
        claimNext();
    }

    /** The printer's next ticket goes out now, not at the next poll (D50 §7.2 amended). */
    private void claimNext() {
        try {
            claimAndSend();
        } catch (RuntimeException e) {
            log.warn("Claiming after a send failed; the next poll claims instead", e);
        }
    }

    /**
     * Stops every stand-in of the JVM from claiming (scheduled runs and the claim after a send alike) until the
     * returned handle is closed: a suite that is the bridge itself claims its jobs alone ({@link PausedPrintStandIns}).
     * Returns once no stand-in is claiming.
     */
    public static AutoCloseable pauseEverywhere() {
        synchronized (CLAIMS) {
            pauses++;
        }
        return () -> {
            synchronized (CLAIMS) {
                pauses--;
            }
        };
    }

    /** In-flight sends finish or are abandoned; an abandoned one is PROCESSING and becomes UNKNOWN after restart. */
    @Override
    public void destroy() throws InterruptedException {
        synchronized (this) {
            senders.shutdown();
        }
        if (!senders.awaitTermination(10, TimeUnit.SECONDS)) {
            senders.shutdownNow();
        }
    }
}
