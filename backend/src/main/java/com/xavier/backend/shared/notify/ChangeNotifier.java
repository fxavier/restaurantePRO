package com.xavier.backend.shared.notify;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.shared.tenancy.TenantScopes;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * POS change notices (D60, spec §9; D185 as amended). A use case calls {@link #notifyAfterCommit} inside its
 * transaction: it runs {@code pg_notify('change_notice', '<tenantId>:<storeId>:<topic>:<id>')} on the transaction's
 * connection, so PostgreSQL delivers the notice only on commit and never after a rollback, to every backend instance.
 * Each instance holds one {@code LISTEN change_notice} connection of its own (not from the pool) and forwards each
 * notice to its streams subscribed to that {@code (tenantId, storeId)}: a stream of another tenant never receives it,
 * even for the same store id. Notices carry ids only: clients re-read through the authorized GET.
 *
 * <p>A lost notice is harmless: clients refresh after reconnecting and poll while the stream is down (the listener
 * reconnects by itself). Notices are written to the streams by one sender thread with a bounded queue (a full queue
 * drops notices), so a slow client never delays anything. A comment heartbeat every 25 s, written by the same sender
 * thread, keeps proxies from closing idle streams; an emitter times out after 30 minutes and is then completed (the
 * client reconnects).
 *
 * <p>On shutdown every stream is completed before the web server's graceful shutdown (D89 (3)): an open stream is an
 * active request, and the server would otherwise wait for it until the shutdown phase times out. Clients reconnect
 * to the next instance.
 */
@Service
public class ChangeNotifier implements SmartLifecycle, DisposableBean {

    /** Stops before {@link WebServerGracefulShutdownLifecycle} (a higher phase stops first). */
    static final int PHASE = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1;

    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);
    static final String CHANNEL = "change_notice";

    private static final Logger log = LoggerFactory.getLogger(ChangeNotifier.class);
    private static final int POLL_MILLIS = 250;
    private static final Duration RECONNECT_AFTER = Duration.ofSeconds(2);

    /** A subscription key: the stream's tenant and store (D186). */
    private record Key(UUID tenantId, UUID storeId) {
    }

    private final Map<Key, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final TenantScopes tenants;
    private final JdbcClient jdbc;
    private final JdbcConnectionDetails database;
    private volatile boolean running;
    private volatile Thread listener;
    private volatile Connection listening;
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1_000), Thread.ofPlatform().name("change-notifier").daemon().factory(),
            (task, executor) -> log.warn("Change notice dropped: the sender queue is full"));

    /** Streams only (no database, no tenant): for a test of the stream lifecycle alone. */
    public ChangeNotifier() {
        this.tenants = null;
        this.jdbc = null;
        this.database = null;
    }

    @Autowired
    public ChangeNotifier(ObjectProvider<TenantScopes> tenants, ObjectProvider<JdbcClient> jdbc,
                          ObjectProvider<JdbcConnectionDetails> database) {
        this.tenants = tenants.getIfAvailable();
        this.jdbc = jdbc.getIfAvailable();
        this.database = database.getIfAvailable();
    }

    /**
     * Sends {@code {topic, id}} to the subscribers of the bound tenant's store once the current transaction commits.
     *
     * @param id the changed aggregate (order, attempt), or {@code null} for a store-wide topic (tables, catalog)
     * @throws IllegalStateException outside a transaction (the notice must follow a commit), or with no tenant bound
     */
    public void notifyAfterCommit(UUID storeId, ChangeTopic topic, UUID id) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(topic, "topic");
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("notifyAfterCommit must be called inside the use case's transaction");
        }
        if (jdbc == null || tenants == null) {
            throw new IllegalStateException("Change notices need the database and a tenant (D185)");
        }
        String payload = tenants.requireTenant() + ":" + storeId + ":" + topic.name() + ":"
                + (id == null ? "" : id.toString());
        jdbc.sql("SELECT pg_notify(:channel, :payload)").param("channel", CHANNEL).param("payload", payload)
                .query().listOfRows();
    }

    /**
     * A new stream of the notices of the bound tenant's store; the caller has authorized it (the store comes from the
     * session). Once {@link #stop()} ran (shutdown), the stream is already completed, so a reconnecting client cannot
     * hold the graceful shutdown again.
     */
    public SseEmitter subscribe(UUID storeId) {
        Objects.requireNonNull(storeId, "storeId");
        Key key = new Key(tenants == null ? null : tenants.requireTenant(), storeId);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        if (!running) {
            emitter.complete();
            return emitter;
        }
        Set<SseEmitter> store = subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArraySet<>());
        store.add(emitter);
        Runnable remove = () -> store.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete(); // ends the stream normally; otherwise the timeout becomes an error dispatch (503)
        });
        emitter.onError(error -> remove.run());
        if (!running) {
            emitter.complete(); // stop() ran between the check above and the registration
        }
        return emitter;
    }

    /** Queued on the sender thread like a notice: one writer per stream, and the scheduler never blocks on a client. */
    @SystemScoped(SystemScope.HOUSEKEEPING)
    @Scheduled(fixedRateString = "${restaurantpro.notifications.heartbeat:PT25S}")
    void heartbeat() {
        sender.execute(() -> subscribers.forEach((key, emitters) -> emitters.forEach(emitter ->
                write(key, emitters, emitter, SseEmitter.event().comment("heartbeat")))));
    }

    /** One notice as PostgreSQL delivered it; a malformed payload is dropped. */
    void received(String payload) {
        parse(payload).ifPresent(parsed -> sender.execute(() -> send(parsed.key(), parsed.notice())));
    }

    private record Parsed(Key key, ChangeNotice notice) {
    }

    private static Optional<Parsed> parse(String payload) {
        String[] parts = payload == null ? new String[0] : payload.split(":", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parsed(new Key(UUID.fromString(parts[0]), UUID.fromString(parts[1])),
                    new ChangeNotice(ChangeTopic.valueOf(parts[2]), parts[3].isEmpty() ? null
                            : UUID.fromString(parts[3]))));
        } catch (IllegalArgumentException e) {
            log.warn("Dropping a malformed change notice");
            return Optional.empty();
        }
    }

    private void send(Key key, ChangeNotice notice) {
        Set<SseEmitter> emitters = subscribers.getOrDefault(key, Set.of());
        emitters.forEach(emitter -> write(key, emitters, emitter,
                SseEmitter.event().name("change").data(notice, MediaType.APPLICATION_JSON)));
    }

    private static void write(Key key, Set<SseEmitter> emitters, SseEmitter emitter,
                              SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping a closed change stream of store {}", key.storeId());
            emitters.remove(emitter);
        }
    }

    @Override
    public void start() {
        running = true;
        if (database == null) {
            return;
        }
        listen(); // the first LISTEN is in place before the context has started
        Thread thread = Thread.ofPlatform().name("change-notice-listener").daemon().unstarted(this::pollUntilStopped);
        listener = thread;
        thread.start();
    }

    /** Completes every open stream, so the graceful shutdown that follows finds no active stream request. */
    @Override
    public void stop() {
        running = false;
        Thread thread = listener;
        if (thread != null) {
            thread.interrupt();
        }
        closeListening();
        completeAll();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void destroy() {
        running = false;
        sender.shutdownNow();
        closeListening();
        completeAll();
    }

    private void completeAll() {
        subscribers.values().forEach(emitters -> emitters.forEach(SseEmitter::complete));
    }

    private void pollUntilStopped() {
        while (running) {
            Connection connection = listening;
            if (connection == null) {
                pause();
                listen();
                continue;
            }
            try {
                for (String payload : notifications(connection)) {
                    received(payload);
                }
            } catch (SQLException | ReflectiveOperationException | RuntimeException e) {
                if (running) {
                    log.warn("The change-notice listener lost its connection; reconnecting", e);
                }
                closeListening();
            }
        }
    }

    /** Opens the instance's own connection, as the pool's role, and listens on {@value #CHANNEL}. */
    private void listen() {
        if (!running) {
            return;
        }
        try {
            Connection connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(),
                    database.getPassword());
            try (Statement statement = connection.createStatement()) {
                statement.execute("LISTEN " + CHANNEL);
            }
            listening = connection;
        } catch (SQLException e) {
            log.warn("The change-notice listener could not connect; retrying in {}", RECONNECT_AFTER, e);
        }
    }

    private void pause() {
        try {
            Thread.sleep(RECONNECT_AFTER);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private void closeListening() {
        Connection connection = listening;
        listening = null;
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("Closing the change-notice connection failed", e);
            }
        }
    }

    /** {@code PGConnection.getNotifications(timeout)}, reached reflectively: the driver is a runtime dependency. */
    private static String[] notifications(Connection connection)
            throws SQLException, ReflectiveOperationException {
        Class<?> pgConnection = Class.forName("org.postgresql.PGConnection");
        Method poll = pgConnection.getMethod("getNotifications", int.class);
        Method parameter = Class.forName("org.postgresql.PGNotification").getMethod("getParameter");
        Object[] notices;
        try {
            notices = (Object[]) poll.invoke(connection.unwrap(pgConnection), POLL_MILLIS);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof SQLException sql) {
                throw sql;
            }
            throw e;
        }
        if (notices == null) {
            return new String[0];
        }
        String[] payloads = new String[notices.length];
        for (int i = 0; i < notices.length; i++) {
            payloads[i] = (String) parameter.invoke(notices[i]);
        }
        return payloads;
    }
}
