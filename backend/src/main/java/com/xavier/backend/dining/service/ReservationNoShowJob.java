package com.xavier.backend.dining.service;

import com.xavier.backend.dining.model.Reservation;
import com.xavier.backend.dining.model.ReservationWindow;
import com.xavier.backend.dining.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P47: every {@code restaurantpro.dining.no-show-interval} (PT1M) a BOOKED reservation whose
 * {@code starts_at + grace} (the store's setting, else 30 min) has passed becomes NO_SHOW as SYSTEM, audited. Each
 * batch is one short transaction that takes its rows {@code FOR UPDATE SKIP LOCKED} (D46): a reservation a request
 * is changing is left for the next run, and the job never waits behind a request. Idempotent: it only selects
 * BOOKED rows, so a second or overlapping run changes nothing. Time is the injected {@code Clock}'s.
 */
@Service
public class ReservationNoShowJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationNoShowJob.class);
    static final int BATCH = 100;

    private final ReservationRepository reservations;
    private final DiningChanges changes;
    private final TransactionTemplate inTransaction;
    private final Clock clock;

    ReservationNoShowJob(ReservationRepository reservations, DiningChanges changes,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.reservations = reservations;
        this.changes = changes;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** @return how many reservations this run marked */
    @Scheduled(fixedDelayString = "${restaurantpro.dining.no-show-interval:PT1M}",
            initialDelayString = "${restaurantpro.dining.no-show-interval:PT1M}")
    public int markDue() {
        Instant now = clock.instant();
        int marked = 0;
        try {
            int batch;
            do {
                batch = inTransaction.execute(status -> markBatch(now));
                marked += batch;
            } while (batch == BATCH);
        } catch (RuntimeException e) {
            log.error("Reservation no-show run stopped after {} reservations; the next run retries", marked, e);
        }
        if (marked > 0) {
            log.info("Reservation no-show run marked {} reservations", marked);
        }
        return marked;
    }

    private int markBatch(Instant now) {
        List<UUID> due = reservations.lockDueForNoShow(now, ReservationWindow.DEFAULT_GRACE_MINUTES, BATCH);
        for (Reservation reservation : reservations.findAllById(due)) {
            reservation.markNoShow(Reservation.SYSTEM, now);
            reservations.saveAndFlush(reservation);
            changes.recordAsSystem("RESERVATION_NO_SHOW", ReservationService.ENTITY, reservation.getId(),
                    reservation.getStoreId(), Map.of("startsAt", reservation.getStartsAt().toString()));
        }
        return due.size();
    }
}
