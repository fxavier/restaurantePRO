package com.xavier.backend.shared.events;

import com.xavier.backend.shared.web.PageResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the event publication registry (D86, D88), in plain SQL on {@code event_publication}: the pending count
 * of a listener (reporting's {@code pendingFacts}), the BackOffice list of incomplete publications, and the gauge
 * {@code event_publications{state="incomplete"|"failed", module}}. The {@code module} tag is the listener's module
 * (the package after {@code com.xavier.backend.}), never an id (§12). {@code failed} means the job gave up after
 * {@code restaurantpro.events.max-attempts}; {@code incomplete} is every other publication not completed yet. The
 * gauge is refreshed every {@code resubmit-interval}; a (state, module) seen once stays reported, at 0 when empty.
 */
@Service
public class EventPublicationStats {

    static final String GAUGE = "event_publications";

    private static final String MODULE = """
            coalesce(substring(listener_id from '^com\\.xavier\\.backend\\.([a-z0-9_]+)\\.'), 'unknown')""";
    private static final String EXHAUSTED = "coalesce(completion_attempts, 0) >= :maxAttempts";

    private final JdbcClient jdbc;
    private final int maxAttempts;
    private final MultiGauge gauge;
    private final Set<Tags> reported = ConcurrentHashMap.newKeySet();

    EventPublicationStats(JdbcClient jdbc, MeterRegistry meters,
                          @Value("${restaurantpro.events.max-attempts:10}") int maxAttempts) {
        this.jdbc = jdbc;
        this.maxAttempts = maxAttempts;
        this.gauge = MultiGauge.builder(GAUGE)
                .description("Event publications not completed, by state and listener module")
                .register(meters);
    }

    /**
     * How many publications of the listeners whose id starts with {@code listenerIdPrefix} (a listener class or
     * package name) are not completed yet. Served by the partial index of V1006.
     */
    @Transactional(readOnly = true)
    public long pendingFor(String listenerIdPrefix) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication
                        WHERE completion_date IS NULL AND listener_id LIKE :prefix ESCAPE '\\'""")
                .param("prefix", escapeLike(listenerIdPrefix) + "%")
                .query(Long.class).single();
    }

    /**
     * {@link #pendingFor(String)} narrowed to the events that concern one key and happened up to a cut-off: the
     * serialized event's top-level {@code keyField} equals {@code keyValue}, and its first present field of
     * {@code instantFields} (an ISO-8601 instant) is at or before {@code cutOff}. A publication without the key, or
     * without any of the instant fields, counts as pending (conservative). The prefix is served by the partial index
     * of V1006; the JSON is read only on the pending rows it returns.
     */
    @Transactional(readOnly = true)
    public long pendingFor(String listenerIdPrefix, String keyField, String keyValue, List<String> instantFields,
                           Instant cutOff) {
        if (instantFields.isEmpty()) {
            throw new IllegalArgumentException("name at least one instant field");
        }
        StringBuilder instant = new StringBuilder("coalesce(");
        for (int i = 0; i < instantFields.size(); i++) {
            instant.append(i == 0 ? "" : ", ").append("p.event ->> :instant").append(i);
        }
        instant.append(")");
        var spec = jdbc.sql("""
                        SELECT count(*) FROM (
                            SELECT serialized_event::jsonb AS event FROM event_publication
                            WHERE completion_date IS NULL AND listener_id LIKE :prefix ESCAPE '\\') p
                        WHERE coalesce(p.event ->> :keyField, :keyValue) = :keyValue
                          AND coalesce((%s)::timestamptz, :cutOff) <= :cutOff""".formatted(instant))
                .param("prefix", escapeLike(listenerIdPrefix) + "%")
                .param("keyField", keyField).param("keyValue", keyValue)
                .param("cutOff", Timestamp.from(cutOff));
        for (int i = 0; i < instantFields.size(); i++) {
            spec = spec.param("instant" + i, instantFields.get(i));
        }
        return spec.query(Long.class).single();
    }

    /** The publications in {@code state}, oldest first. The page's sort is ignored. */
    @Transactional(readOnly = true)
    public PageResponse<EventPublicationView> list(EventPublicationState state, Pageable pageable) {
        boolean failed = state == EventPublicationState.FAILED;
        String where = "completion_date IS NULL AND (" + EXHAUSTED + ") = :failed";
        List<EventPublicationView> content = jdbc.sql("""
                        SELECT id, listener_id, event_type, publication_date, last_resubmission_date,
                               coalesce(completion_attempts, 0) AS attempts, status
                        FROM event_publication WHERE %s
                        ORDER BY publication_date, id LIMIT :limit OFFSET :offset""".formatted(where))
                .param("maxAttempts", maxAttempts).param("failed", failed)
                .param("limit", pageable.getPageSize()).param("offset", pageable.getOffset())
                .query((row, rowNumber) -> new EventPublicationView(row.getObject("id", UUID.class),
                        row.getString("listener_id"), row.getString("event_type"),
                        row.getTimestamp("publication_date").toInstant(),
                        instantOrNull(row.getTimestamp("last_resubmission_date")), row.getInt("attempts"),
                        row.getString("status"), state))
                .list();
        long total = jdbc.sql("SELECT count(*) FROM event_publication WHERE " + where)
                .param("maxAttempts", maxAttempts).param("failed", failed)
                .query(Long.class).single();
        return new PageResponse<>(content, pageable.getPageNumber(), pageable.getPageSize(), total);
    }

    /** Recounts the gauge rows. */
    @Scheduled(fixedDelayString = "${restaurantpro.events.resubmit-interval:PT1M}")
    @Transactional(readOnly = true)
    void refresh() {
        Map<Tags, Long> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT CASE WHEN %s THEN 'failed' ELSE 'incomplete' END AS state, %s AS module,
                               count(*) AS publications
                        FROM event_publication WHERE completion_date IS NULL GROUP BY 1, 2""".formatted(EXHAUSTED, MODULE))
                .param("maxAttempts", maxAttempts)
                .query(row -> {
                    counts.put(Tags.of("state", row.getString("state"), "module", row.getString("module")),
                            row.getLong("publications"));
                });
        reported.addAll(counts.keySet());
        gauge.register(reported.stream()
                .map(tags -> MultiGauge.Row.of(tags, counts.getOrDefault(tags, 0L)))
                .toList(), true);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
