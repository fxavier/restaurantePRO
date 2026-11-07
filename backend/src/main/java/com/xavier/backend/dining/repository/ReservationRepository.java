package com.xavier.backend.dining.repository;

import com.xavier.backend.dining.model.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /** Only the store of a reservation, to authorize before loading it (D42). */
    @Query("select r.storeId from Reservation r where r.id = :id")
    Optional<UUID> findStoreIdById(UUID id);

    /** The calendar: every state, {@code from} inclusive, {@code to} exclusive. */
    List<Reservation> findByStoreIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscIdAsc(
            UUID storeId, Instant from, Instant to);

    /** Per table, the earliest BOOKED start in {@code (after, until]} (the map indicator, P47). */
    @Query("""
            select new com.xavier.backend.dining.repository.NextReservation(t.tableId, min(r.startsAt))
            from Reservation r join r.tables t
            where r.storeId = :storeId and r.status = com.xavier.backend.dining.model.dto.ReservationStatus.BOOKED
              and r.startsAt > :after and r.startsAt <= :until
            group by t.tableId""")
    List<NextReservation> nextByTable(UUID storeId, Instant after, Instant until);

    @Query("""
            select min(r.startsAt) from Reservation r join r.tables t
            where t.tableId = :tableId and r.status = com.xavier.backend.dining.model.dto.ReservationStatus.BOOKED
              and r.startsAt > :after and r.startsAt <= :until""")
    Optional<Instant> nextForTable(UUID tableId, Instant after, Instant until);

    /**
     * BOOKED reservations whose no-show grace has passed at {@code now} (the store's setting, else
     * {@code defaultGrace}), locked {@code FOR UPDATE SKIP LOCKED} (D46): a row a request is changing is left for
     * the next run.
     */
    @Query(nativeQuery = true, value = """
            SELECT r.id FROM dining.reservation r
            LEFT JOIN dining.dining_settings s ON s.store_id = r.store_id
            WHERE r.status = 'BOOKED' AND r.starts_at <= :now
              AND r.starts_at + make_interval(mins => coalesce(s.no_show_grace_minutes, :defaultGrace)) <= :now
            ORDER BY r.starts_at, r.id
            LIMIT :limit
            FOR UPDATE OF r SKIP LOCKED""")
    List<UUID> lockDueForNoShow(Instant now, int defaultGrace, int limit);
}
