package com.xavier.backend.shared.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Row-level security refusals (D170): recognised by their SQL state, counted as
 * {@code store_scope_violations_total{kind}}, logged at ERROR with the scope origin, and remembered for 24 h so the
 * {@code STORE_SCOPE_VIOLATION} alert can report them. A refusal is always a bug: {@code AccessPolicy} allowed what
 * the database refused. Classified at the two catch points, the exception handler (requests) and the scope advisor and
 * task wrapper (listeners, jobs, tasks).
 */
@Component
public class StoreScopeViolations {

    /** How far back {@link #lastDay} looks. */
    public static final Duration MEMORY = Duration.ofHours(24);

    static final String METRIC = "store_scope_violations";
    private static final int MAX_REMEMBERED_PER_KIND = 1_000;
    private static final String RLS_MESSAGE = "row-level security";
    private static final Logger log = LoggerFactory.getLogger(StoreScopeViolations.class);

    /** What the database refused. */
    public enum Kind {
        /** {@code RS001}: a protected table touched with no store scope. */
        SCOPE_MISSING("scope_missing", "STORE_SCOPE_MISSING"),
        /** {@code 42501} from a policy's {@code WITH CHECK}: a written row outside the scope. */
        WRITE_REFUSED("write_refused", "STORE_SCOPE_VIOLATION"),
        /** {@code RS002}: a row's store (or a child's parent) changed. */
        STORE_CHANGE("store_change", "STORE_CHANGE_REFUSED");

        private final String label;
        private final String code;

        Kind(String label, String code) {
            this.label = label;
            this.code = code;
        }

        /** The metric's {@code kind} label. */
        public String label() {
            return label;
        }

        /** The coded error of the API (a 500). */
        public String code() {
            return code;
        }
    }

    /** The violations of one kind in the last {@link #MEMORY}. */
    public record Recent(long count, Instant first, Instant last) {
    }

    private final Clock clock;
    private final Map<Kind, Counter> counters = new EnumMap<>(Kind.class);
    private final Map<Kind, Deque<Instant>> remembered = new EnumMap<>(Kind.class);

    public StoreScopeViolations(MeterRegistry registry, Clock clock) {
        this.clock = clock;
        for (Kind kind : Kind.values()) {
            counters.put(kind, Counter.builder(METRIC).tag("kind", kind.label())
                    .description("Row-level security refusals: a store scope bug").register(registry));
            remembered.put(kind, new ConcurrentLinkedDeque<>());
        }
    }

    /** The kind of refusal anywhere in the cause chain, by SQL state; empty for any other error. */
    public static Optional<Kind> classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql) {
                Optional<Kind> kind = kindOf(sql);
                if (kind.isPresent()) {
                    return kind;
                }
            }
        }
        return Optional.empty();
    }

    /** Counts, logs and remembers one refusal; {@code origin} names who ran (never an id of a row). */
    public void record(Kind kind, String origin) {
        counters.get(kind).increment();
        log.error("Store scope violation {} ({}) under scope origin {}", kind.code(), kind.label(), origin);
        Deque<Instant> instants = remembered.get(kind);
        instants.addLast(clock.instant());
        while (instants.size() > MAX_REMEMBERED_PER_KIND) {
            instants.pollFirst();
        }
    }

    /** Per kind, the refusals of the last 24 h; a kind with none is absent. */
    public Map<Kind, Recent> lastDay() {
        Instant since = clock.instant().minus(MEMORY);
        Map<Kind, Recent> recent = new EnumMap<>(Kind.class);
        remembered.forEach((kind, instants) -> {
            while (!instants.isEmpty() && !instants.peekFirst().isAfter(since)) {
                instants.pollFirst();
            }
            Instant first = instants.peekFirst();
            Instant last = instants.peekLast();
            if (first != null && last != null) {
                recent.put(kind, new Recent(instants.size(), first, last));
            }
        });
        return recent;
    }

    private static Optional<Kind> kindOf(SQLException sql) {
        String state = sql.getSQLState();
        if ("RS001".equals(state)) {
            return Optional.of(Kind.SCOPE_MISSING);
        }
        if ("RS002".equals(state)) {
            return Optional.of(Kind.STORE_CHANGE);
        }
        if ("42501".equals(state) && String.valueOf(sql.getMessage()).contains(RLS_MESSAGE)) {
            return Optional.of(Kind.WRITE_REFUSED);
        }
        return Optional.empty();
    }
}
