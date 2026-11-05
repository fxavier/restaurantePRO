package com.xavier.backend.reporting.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.reporting.exception.RebuildRunningException;
import com.xavier.backend.reporting.exception.ReportStoreNotFoundException;
import com.xavier.backend.reporting.model.dto.RebuildRequest;
import com.xavier.backend.reporting.model.dto.RebuildView;
import com.xavier.backend.reporting.repository.RebuildRuns;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Starts a rebuild of a store's projections over a business-date range (D84): one RUNNING run per store
 * ({@code rebuild_run_running_uk}), audited, and run by {@link RebuildRunner} on the application task executor once
 * the request has committed. A run a restart interrupted (started before this JVM) is marked FAILED when the
 * application is ready, so it never blocks the next one; a run started since is a live one and is left alone.
 */
@Service
public class RebuildService {

    static final String REQUESTED = "REPORT_REBUILD_REQUESTED";
    static final String INTERRUPTED = "Interrupted by a restart: rebuild the range again.";

    private final AccessPolicy access;
    private final OrganizationApi organization;
    private final RebuildRuns runs;
    private final RebuildRunner runner;
    private final AuditLog auditLog;
    private final TaskExecutor executor;
    private final Clock clock;

    RebuildService(AccessPolicy access, OrganizationApi organization, RebuildRuns runs, RebuildRunner runner,
                   AuditLog auditLog, @Qualifier("applicationTaskExecutor") TaskExecutor executor, Clock clock) {
        this.access = access;
        this.organization = organization;
        this.runs = runs;
        this.runner = runner;
        this.auditLog = auditLog;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Needs {@code REPORT_REBUILD} in the store.
     *
     * @throws RebuildRunningException (409 {@code REBUILD_RUNNING}) while another rebuild of the store runs
     */
    @Transactional
    public RebuildView start(RebuildRequest request) {
        access.require(Permission.REPORT_REBUILD, request.storeId());
        ReportRange range = new ReportRange(request.storeId(), request.from(), request.to());
        if (!organization.storeExists(range.storeId())) {
            throw new ReportStoreNotFoundException(range.storeId());
        }
        UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        try {
            runs.start(id, range.storeId(), range.from(), range.to(), clock.instant());
        } catch (DuplicateKeyException e) {
            // plain JDBC: the constraint is named in the driver's message (UniqueConstraints reads Hibernate's)
            if (String.valueOf(e.getMessage()).contains(RebuildRuns.ONE_RUNNING_PER_STORE)) {
                throw new RebuildRunningException(range.storeId());
            }
            throw e;
        }
        auditLog.recordAsCurrentActor(REQUESTED, "ReportRebuild", id.toString(), range.storeId(), null, null,
                Map.of("from", range.from().toString(), "to", range.to().toString()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(() -> runner.run(id));
            }
        });
        return runs.find(id).orElseThrow();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void abandonInterruptedRuns() {
        Instant processStart = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        runs.abandonRunning(processStart, INTERRUPTED, clock.instant());
    }
}
