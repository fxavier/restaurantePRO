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
     * The worker's due work (D50 §7.2), oldest first, locked so overlapping runs never take the same job. A HOLD or
     * inactive printer keeps its jobs, and a printer with a send in flight ({@code busyPrinters}, never empty) is
     * skipped so its tickets come out in order.
     */
    @Query(value = """
            SELECT j.* FROM production.print_job j JOIN production.printer p ON p.id = j.printer_id
            WHERE j.status IN ('QUEUED', 'RETRY_WAIT') AND j.next_attempt_at <= :now
              AND p.mode <> 'HOLD' AND p.active AND j.printer_id NOT IN (:busyPrinters)
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

    long countByStatus(PrintJobStatus status);

    /** REDIRECT (D56): the undelivered jobs of {@code from} go to {@code to}; the version moves like an edit. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PrintJob j set j.printerId = :to, j.version = j.version + 1
            where j.printerId = :from and j.status in :statuses""")
    int moveJobs(@Param("from") UUID from, @Param("to") UUID to,
                 @Param("statuses") Collection<PrintJobStatus> statuses);
}
