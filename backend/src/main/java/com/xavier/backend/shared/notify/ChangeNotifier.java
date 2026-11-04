package com.xavier.backend.shared.notify;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * POS change notices (D60, spec §9). A use case calls {@link #notifyAfterCommit} inside its transaction; the notice
 * {@code {topic, id}} goes to every stream subscribed to that store only after the commit, and never after a
 * rollback. Notices carry no data: clients re-read through the authorized GET.
 *
 * <p>The registry is in memory (one backend per installation, D19) and a lost notice is harmless: clients refresh
 * after reconnecting and poll while the stream is down. Notices are written off the committing thread by one
 * sender thread with a bounded queue (a full queue drops notices), so a slow client never delays a commit. A
 * comment heartbeat every 25 s, written by the same sender thread, keeps proxies from closing idle streams; an
 * emitter times out after 30 minutes and is then completed (the client reconnects).
 */
@Service
public class ChangeNotifier implements DisposableBean {

    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(ChangeNotifier.class);

    private final Map<UUID, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1_000), Thread.ofPlatform().name("change-notifier").daemon().factory(),
            (task, executor) -> log.warn("Change notice dropped: the sender queue is full"));

    /**
     * Sends {@code {topic, id}} to the store's subscribers after the current transaction commits.
     *
     * @param id the changed aggregate (order, attempt), or {@code null} for a store-wide topic (tables, catalog)
     * @throws IllegalStateException outside a transaction: the notice must follow a commit
     */
    public void notifyAfterCommit(UUID storeId, ChangeTopic topic, UUID id) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(topic, "topic");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("notifyAfterCommit must be called inside the use case's transaction");
        }
        ChangeNotice notice = new ChangeNotice(topic, id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sender.execute(() -> send(storeId, notice));
            }
        });
    }

    /** A new stream of the store's notices; the caller has authorized it (the store comes from the session). */
    public SseEmitter subscribe(UUID storeId) {
        Objects.requireNonNull(storeId, "storeId");
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        Set<SseEmitter> store = subscribers.computeIfAbsent(storeId, key -> new CopyOnWriteArraySet<>());
        store.add(emitter);
        Runnable remove = () -> store.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete(); // ends the stream normally; otherwise the timeout becomes an error dispatch (503)
        });
        emitter.onError(error -> remove.run());
        return emitter;
    }

    /** Queued on the sender thread like a notice: one writer per stream, and the scheduler never blocks on a client. */
    @Scheduled(fixedRateString = "${restaurantpro.notifications.heartbeat:PT25S}")
    void heartbeat() {
        sender.execute(() -> subscribers.forEach((storeId, emitters) -> emitters.forEach(emitter ->
                write(storeId, emitters, emitter, SseEmitter.event().comment("heartbeat")))));
    }

    private void send(UUID storeId, ChangeNotice notice) {
        Set<SseEmitter> emitters = subscribers.getOrDefault(storeId, Set.of());
        emitters.forEach(emitter -> write(storeId, emitters, emitter,
                SseEmitter.event().name("change").data(notice, MediaType.APPLICATION_JSON)));
    }

    private static void write(UUID storeId, Set<SseEmitter> emitters, SseEmitter emitter,
                              SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping a closed change stream of store {}", storeId);
            emitters.remove(emitter);
        }
    }

    @Override
    public void destroy() {
        sender.shutdownNow();
        subscribers.values().forEach(emitters -> emitters.forEach(SseEmitter::complete));
    }
}
