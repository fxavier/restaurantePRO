package com.xavier.backend.shared.events;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * D88: incomplete event publications are recovered without a restart. Every {@code restaurantpro.events
 * .resubmit-interval} (PT1M) the job hands each publication that has been untouched for {@code resubmit-older-than}
 * (PT5M, measured from its last delivery: the publication, or its last resubmission) and has had fewer than
 * {@code max-attempts} (10) attempts back to its listener, through Modulith's {@link IncompleteEventPublications}.
 * Modulith counts the first delivery as attempt 1, so a publication gets at most 9 resubmissions; then it stays
 * {@link EventPublicationState#FAILED} for a person ({@link #resubmit}, {@link #resubmitOlderThan}) and the
 * {@code event_publications} gauge shows it. Listeners are idempotent (D19), so a resubmission that overlaps a slow
 * original is harmless. Startup republishing stays on. Completed publications older than
 * {@code completed-retention} (P30D) are deleted daily.
 *
 * <p>Resubmission runs on the caller's thread outside any transaction; the listeners themselves run asynchronously.
 */
@Service
public class EventPublicationRecovery {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationRecovery.class);

    private final IncompleteEventPublications incomplete;
    private final CompletedEventPublications completed;
    private final EventPublicationStats stats;
    private final Clock clock;
    private final Duration olderThan;
    private final int maxAttempts;
    private final Duration completedRetention;

    EventPublicationRecovery(IncompleteEventPublications incomplete, CompletedEventPublications completed,
                             EventPublicationStats stats, Clock clock,
                             @Value("${restaurantpro.events.resubmit-older-than:PT5M}") Duration olderThan,
                             @Value("${restaurantpro.events.max-attempts:10}") int maxAttempts,
                             @Value("${restaurantpro.events.completed-retention:P30D}") Duration completedRetention) {
        requirePositive(olderThan, "resubmit-older-than");
        requirePositive(completedRetention, "completed-retention");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("restaurantpro.events.max-attempts must be at least 1: " + maxAttempts);
        }
        this.incomplete = incomplete;
        this.completed = completed;
        this.stats = stats;
        this.clock = clock;
        this.olderThan = olderThan;
        this.maxAttempts = maxAttempts;
        this.completedRetention = completedRetention;
    }

    /** The scheduled retry; its first run waits one interval, so a starting instance relies on startup republishing. */
    @Scheduled(fixedDelayString = "${restaurantpro.events.resubmit-interval:PT1M}",
            initialDelayString = "${restaurantpro.events.resubmit-interval:PT1M}")
    void resubmitDue() {
        Instant untouchedSince = clock.instant().minus(olderThan);
        int handed = resubmitWhere(publication -> publication.getCompletionAttempts() < maxAttempts
                && publication.getLastPublicationDate().isBefore(untouchedSince));
        if (handed > 0) {
            log.info("Resubmitting {} incomplete event publications untouched for {}", handed, olderThan);
        }
        stats.refresh();
    }

    @Scheduled(cron = "${restaurantpro.events.purge-cron:0 45 3 * * *}")
    void purgeCompleted() {
        completed.deletePublicationsOlderThan(completedRetention);
        log.info("Deleted completed event publications older than {}", completedRetention);
    }

    /**
     * A person's resubmission of the given incomplete publications, whatever their attempts (unknown or completed ids
     * are ignored).
     *
     * @return how many of them were incomplete and handed to their listener again
     */
    public int resubmit(Collection<UUID> publicationIds) {
        Set<UUID> ids = Set.copyOf(publicationIds);
        return resubmitWhere(publication -> ids.contains(publication.getIdentifier()));
    }

    /**
     * A person's resubmission of every incomplete publication published more than {@code age} ago, whatever its
     * attempts.
     *
     * @return how many were handed to their listener again
     */
    public int resubmitOlderThan(Duration age) {
        requirePositive(age, "olderThan");
        Instant publishedBefore = clock.instant().minus(age);
        return resubmitWhere(publication -> publication.getPublicationDate().isBefore(publishedBefore));
    }

    /**
     * Counts what the filter selected. Modulith then skips a publication another resubmission already claimed, so the
     * count can be higher than the deliveries in a race.
     */
    private int resubmitWhere(Predicate<EventPublication> filter) {
        AtomicInteger selected = new AtomicInteger();
        incomplete.resubmitIncompletePublications(publication -> {
            boolean due = filter.test(publication);
            if (due) {
                selected.incrementAndGet();
            }
            return due;
        });
        return selected.get();
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("restaurantpro.events." + name + " must be positive: " + duration);
        }
    }
}
