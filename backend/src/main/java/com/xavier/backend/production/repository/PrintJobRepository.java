package com.xavier.backend.production.repository;

import com.xavier.backend.production.model.JobKind;
import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.PrintJobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrintJobRepository extends JpaRepository<PrintJob, UUID>, JpaSpecificationExecutor<PrintJob> {

    /**
     * The worker's due work (D50 §7.2 amended): each printer's head job (its oldest QUEUED or RETRY_WAIT job), if
     * it is due, the oldest heads first, locked so overlapping runs never take the same job. A head that waits to
     * retry blocks its printer, so tickets come out in order, and a printer with a job PROCESSING (a send in flight
     * on any worker sharing this database) is skipped, so it has one send at a time. A HOLD, IGNORE (a suppression,
     * never a failure: spec §8.6) or inactive printer keeps its jobs pending. The outer status check re-reads a row
     * that a concurrent claim committed while this one waited, so a job is never claimed twice; matching on the
     * printer too drops a job that a REDIRECT moved meanwhile, whose new printer may already be sending. Drawer
     * kicks are not queued work: {@link #lockDueKicks} takes them.
     */
    @Query(value = """
            SELECT j.* FROM production.print_job j
            WHERE (j.id, j.printer_id) IN (
                SELECT DISTINCT ON (d.printer_id) d.id, d.printer_id
                FROM production.print_job d JOIN production.printer p ON p.id = d.printer_id
                WHERE d.status IN ('QUEUED', 'RETRY_WAIT') AND p.mode NOT IN ('HOLD', 'IGNORE') AND p.active
                  AND NOT EXISTS (SELECT 1 FROM production.production_ticket t
                                  WHERE t.id = d.ticket_id AND t.source_type = 'DRAWER_KICK')
                  AND NOT EXISTS (SELECT 1 FROM production.print_job x
                                  WHERE x.printer_id = d.printer_id AND x.status = 'PROCESSING')
                ORDER BY d.printer_id, d.created_at, d.id)
              AND j.status IN ('QUEUED', 'RETRY_WAIT') AND j.next_attempt_at <= :now
            ORDER BY j.created_at
            LIMIT :limit
            FOR UPDATE OF j SKIP LOCKED""", nativeQuery = true)
    List<PrintJob> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * D158: the QUEUED drawer kicks, oldest first, whatever their printer's mode (a kick is never held: one on a
     * printer that is not NORMAL fails at once) and ahead of the printer's queued work, but not while the printer
     * has a send in flight (one connection at a time), locked as {@link #lockDue} does.
     */
    @Query(value = """
            SELECT j.* FROM production.print_job j JOIN production.production_ticket t ON t.id = j.ticket_id
            WHERE t.source_type = 'DRAWER_KICK' AND j.status = 'QUEUED'
              AND NOT EXISTS (SELECT 1 FROM production.print_job x
                              WHERE x.printer_id = j.printer_id AND x.status = 'PROCESSING')
            ORDER BY j.created_at
            LIMIT :limit
            FOR UPDATE OF j SKIP LOCKED""", nativeQuery = true)
    List<PrintJob> lockDueKicks(@Param("limit") int limit);

    /** PROCESSING jobs whose send started before {@code startedBefore}: a crash during the send. */
    @Query(value = """
            SELECT * FROM production.print_job
            WHERE status = 'PROCESSING' AND started_at < :startedBefore
            ORDER BY started_at
            LIMIT 100
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<PrintJob> lockExpiredLeases(@Param("startedBefore") Instant startedBefore);

    @Query("select j.storeId from PrintJob j where j.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);

    long countByTicketIdAndKindAndRetryOfIsNull(UUID ticketId, JobKind kind);

    /** How many jobs are in each status (statuses with none are absent): one scrape of {@code print_jobs}. */
    @Query("select j.status as status, count(j) as jobs from PrintJob j group by j.status")
    List<StatusCount> countGroupedByStatus();

    /**
     * D150 {@code PRINT_JOBS_ATTENTION}: per store, the FAILED or UNKNOWN jobs nobody resolved, created since
     * {@code since} (the partial index {@code print_job_unresolved_ix}). Drawer kicks are not counted (D158).
     */
    @Query("""
            select new com.xavier.backend.production.repository.StoreJobCount(j.storeId, count(j), 0L, min(j.createdAt))
            from PrintJob j, ProductionTicket t
            where t.id = j.ticketId
              and t.sourceType <> com.xavier.backend.production.model.TicketSource.DRAWER_KICK
              and j.status in (com.xavier.backend.production.model.PrintJobStatus.FAILED,
                               com.xavier.backend.production.model.PrintJobStatus.UNKNOWN)
              and j.resolution is null and j.createdAt >= :since
            group by j.storeId""")
    List<StoreJobCount> countUnresolvedSince(@Param("since") Instant since);

    /**
     * D150 {@code PRINT_QUEUE_STALLED}: per store, the QUEUED or RETRY_WAIT jobs on a NORMAL printer (on HOLD or
     * IGNORE, waiting is intended), and how many of them were created before {@code lateBefore}. Drawer kicks are
     * not counted (D158).
     */
    @Query("""
            select new com.xavier.backend.production.repository.StoreJobCount(j.storeId, count(j),
                sum(case when j.createdAt < :lateBefore then 1L else 0L end), min(j.createdAt))
            from PrintJob j, Printer p, ProductionTicket t
            where p.id = j.printerId and p.mode = com.xavier.backend.production.model.PrinterMode.NORMAL
              and t.id = j.ticketId
              and t.sourceType <> com.xavier.backend.production.model.TicketSource.DRAWER_KICK
              and j.status in (com.xavier.backend.production.model.PrintJobStatus.QUEUED,
                               com.xavier.backend.production.model.PrintJobStatus.RETRY_WAIT)
            group by j.storeId""")
    List<StoreJobCount> countWaitingOnNormalPrinters(@Param("lateBefore") Instant lateBefore);

    interface StatusCount {

        PrintJobStatus getStatus();

        long getJobs();
    }

    /** REDIRECT (D56): the undelivered jobs of {@code from} go to {@code to}; the version moves like an edit. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PrintJob j set j.printerId = :to, j.version = j.version + 1
            where j.printerId = :from and j.status in :statuses""")
    int moveJobs(@Param("from") UUID from, @Param("to") UUID to,
                 @Param("statuses") Collection<PrintJobStatus> statuses);
}
