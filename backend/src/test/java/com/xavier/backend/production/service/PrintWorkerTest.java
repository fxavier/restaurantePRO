package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.adapter.PrinterTransport;
import com.xavier.backend.production.adapter.SendOutcome;
import com.xavier.backend.production.adapter.SendResult;
import com.xavier.backend.production.model.PrinterAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The worker without a database or a scheduler: an in-memory queue that, like the claim query, hands out each
 * printer's oldest job unless it has one in flight (claimed, outcome not yet recorded), and an accepting transport.
 */
class PrintWorkerTest {

    static final PrinterAddress ADDRESS = new PrinterAddress("127.0.0.1", 9100);

    final InMemoryQueue queue = new InMemoryQueue();
    final AcceptingTransport transport = new AcceptingTransport();
    final PrintWorker worker = new PrintWorker(queue, transport, 4);

    @AfterEach
    void stop() throws InterruptedException {
        worker.destroy();
    }

    /**
     * D50 §7.2 (amended): a printer's next job is claimed as soon as its previous send is recorded, never while it
     * is on the wire. One poll drains the whole backlog, where waiting for the next poll would take one poll interval
     * (1 s) per job, 50 s here; the test never polls for the drain, so the old behaviour cannot pass however long it
     * waits.
     */
    @Test
    void onePollDrainsAPrintersBacklogInOrderOneSendAtATime() throws InterruptedException {
        UUID printer = UUID.randomUUID();
        List<UUID> backlog = queue.add(printer, 50);
        transport.expect(50);

        worker.poll();
        assertThat(transport.firstStarted.await(5, TimeUnit.SECONDS)).as("first send started").isTrue();
        assertThat(worker.poll()).as("claimed while the first send is on the wire").isZero();
        assertThat(transport.sent).hasSize(1);
        transport.releaseFirst.countDown();

        assertThat(transport.allSent.await(5, TimeUnit.SECONDS)).as("all 50 jobs sent after one poll").isTrue();
        assertThat(transport.sent).containsExactlyElementsOf(backlog.stream().map(queue::payloadOf).toList());
    }

    static final class InMemoryQueue extends PrintQueue {

        private final Map<UUID, Deque<Dispatch>> pendingByPrinter = new HashMap<>();
        private final Map<UUID, String> payloads = new HashMap<>();
        private final Set<UUID> inFlight = new HashSet<>();
        private final Map<UUID, UUID> printerOf = new HashMap<>();

        InMemoryQueue() {
            super(null, null, null, null, null, null);
        }

        synchronized List<UUID> add(UUID printer, int count) {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                UUID id = UUID.randomUUID();
                payloads.put(id, "job " + i);
                printerOf.put(id, printer);
                pendingByPrinter.computeIfAbsent(printer, p -> new ArrayDeque<>())
                        .add(new Dispatch(id, printer, ADDRESS, payloads.get(id).getBytes()));
                ids.add(id);
            }
            return ids;
        }

        synchronized String payloadOf(UUID job) {
            return payloads.get(job);
        }

        @Override
        public synchronized List<Dispatch> claimDue(int limit) {
            List<Dispatch> claimed = new ArrayList<>();
            for (Map.Entry<UUID, Deque<Dispatch>> pending : pendingByPrinter.entrySet()) {
                if (claimed.size() < limit && !inFlight.contains(pending.getKey())
                        && !pending.getValue().isEmpty()) {
                    claimed.add(pending.getValue().poll());
                    inFlight.add(pending.getKey());
                }
            }
            return claimed;
        }

        @Override
        public int expireLeases() {
            return 0;
        }

        @Override
        public synchronized void record(UUID jobId, SendOutcome outcome) {
            inFlight.remove(printerOf.get(jobId));
        }
    }

    /** Accepts every send; the first one stays on the wire until {@code releaseFirst}. */
    static final class AcceptingTransport implements PrinterTransport {

        final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allSent;

        void expect(int sends) {
            allSent = new CountDownLatch(sends);
        }

        @Override
        public SendResult send(PrinterAddress address, byte[] payload, boolean probeStatus) {
            sent.add(new String(payload));
            if (firstStarted.getCount() > 0) {
                firstStarted.countDown();
                awaitRelease();
            }
            allSent.countDown();
            return SendResult.of(SendOutcome.ACCEPTED);
        }

        private void awaitRelease() {
            try {
                releaseFirst.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
