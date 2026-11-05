package com.xavier.backend.production.service;

import com.xavier.backend.production.adapter.PrinterTransport;
import com.xavier.backend.production.adapter.SendOutcome;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The print worker (D50 §7.2, D46). Each run, on the scheduler thread, expires lost leases and claims due jobs (short
 * transactions), then hands each send to a dedicated bounded pool: the shared scheduler thread never waits on a
 * printer, and no send holds a transaction or a connection (D48; the transport checks). A run claims no more than
 * the pool has free threads, at most one job per printer, and none for a printer with a send in flight. When a send
 * is recorded its sender claims again, so a printer's backlog drains at its own speed rather than one job per poll.
 */
@Service
public class PrintWorker implements DisposableBean {

    static final int MAX_CLAIM = 10;

    private static final Logger log = LoggerFactory.getLogger(PrintWorker.class);

    private final PrintQueue queue;
    private final PrinterTransport transport;
    private final Semaphore freeSenders;
    private final ThreadPoolExecutor senders;
    private final Set<UUID> busyPrinters = ConcurrentHashMap.newKeySet();

    PrintWorker(PrintQueue queue, PrinterTransport transport,
                @Value("${restaurantpro.production.worker.senders:4}") int senders) {
        this.queue = queue;
        this.transport = transport;
        this.freeSenders = new Semaphore(senders);
        this.senders = new ThreadPoolExecutor(senders, senders, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(senders), Thread.ofPlatform().name("print-sender-", 0).daemon().factory());
    }

    /** One run; also what tests call. */
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
        List<Dispatch> claimed = queue.claimDue(Math.min(free, MAX_CLAIM), Set.copyOf(busyPrinters));
        for (Dispatch dispatch : claimed) {
            freeSenders.acquireUninterruptibly();
            busyPrinters.add(dispatch.printerId());
            senders.execute(() -> deliver(dispatch));
        }
        return claimed.size();
    }

    private void deliver(Dispatch dispatch) {
        try {
            SendOutcome outcome = transport.send(dispatch.address(), dispatch.payload());
            queue.record(dispatch.jobId(), outcome);
        } catch (RuntimeException e) {
            // the job stays PROCESSING; its lease turns it UNKNOWN for a person to resolve
            log.error("Print job {} was not recorded after its send", dispatch.jobId(), e);
        } finally {
            busyPrinters.remove(dispatch.printerId());
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
