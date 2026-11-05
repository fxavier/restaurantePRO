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
     * The worker's due work (D50 §7.2 amended): the oldest due job of each printer, then the oldest of those first,
     * locked so overlapping runs never take the same job. One per printer keeps a printer's tickets in order and lets
     * a backlog on one printer never crowd another out of the {@code limit}. A HOLD, IGNORE (a suppression, never a
     * failure: spec §8.6) or inactive printer keeps its jobs pending, and a printer with a send in flight
     * ({@code busyPrinters}, never empty) is skipped. The outer status check re-reads a row a concurrent claim
     * committed while this one waited, so a job is never claimed twice.
     */
    @Query(value = """
            SELECT j.* FROM production.print_job j
            WHERE j.id IN (
                SELECT DISTINCT ON (d.printer_id) d.id
                FROM production.print_job d JOIN production.printer p ON p.id = d.printer_id
                WHERE d.status IN ('QUEUED', 'RETRY_WAIT') AND d.next_attempt_at <= :now
                  AND p.mode NOT IN ('HOLD', 'IGNORE') AND p.active AND d.printer_id NOT IN (:busyPrinters)
                ORDER BY d.printer_id, d.created_at, d.id)
              AND j.status IN ('QUEUED', 'RETRY_WAIT')
            ORDER BY j.created_at
            LIMIT :limit
            FOR UPDATE OF j SKIP LOCKED""", nativeQuery = true)
    List<PrintJob> lockDue(@Param("now") Instant now, @Param("busyPrinters") Collection<UUID> busyPrinters,
                           @Param("limit") int limit);

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
