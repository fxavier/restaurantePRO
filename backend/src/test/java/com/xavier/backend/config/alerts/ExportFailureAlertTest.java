package com.xavier.backend.config.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import com.xavier.backend.audit.model.dto.AuditFilter;
import com.xavier.backend.audit.service.AuditExport;
import com.xavier.backend.audit.service.AuditExportService;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.inventory.repository.InventoryQueries;
import com.xavier.backend.inventory.service.InventoryReportService;
import com.xavier.backend.reporting.model.dto.ExportRequest;
import com.xavier.backend.reporting.service.ReportExportService;
import com.xavier.backend.reporting.service.ReportService;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.ExportMonitor;
import com.xavier.backend.shared.ops.OperationalAlert;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A63, D150 EXPORT_FAILED: a failure in each of the three CSV exports (report, audit, inventory) is counted in
 * {@code exports_total{kind, outcome="failure"}} and raises the installation-wide warning naming its kind. The report
 * and inventory reads are spies made to throw; the audit download gets a writer that breaks.
 */
@AlertsIntegrationTest
@WithActor(permissions = {Permission.REPORT_EXPORT, Permission.REPORT_VIEW, Permission.INVENTORY_VIEW,
        Permission.AUDIT_VIEW, Permission.AUDIT_EXPORT})
class ExportFailureAlertTest {

    @Autowired
    ReportExportService reportExports;

    @Autowired
    ReportService reports;

    @Autowired
    InventoryReportService inventoryReports;

    @Autowired
    InventoryQueries inventoryQueries;

    @Autowired
    AuditExportService auditExports;

    @Autowired
    ExportMonitor monitor;

    @Autowired
    MeterRegistry meters;

    @Autowired
    Clock clock;

    @Autowired
    CashFixtures cash;

    UUID store;

    @BeforeEach
    void aStore() throws Exception {
        SecurityContext actor = SecurityContextHolder.getContext();
        store = cash.newStore();
        SecurityContextHolder.setContext(actor); // the fixture works as its own actor and clears the context
    }

    @Test
    void aFailedReportExportIsCountedAndAlerted() {
        doThrow(new IllegalStateException("forced")).when(reports).sales(any(), any(), any(), any());
        double before = failures(ExportMonitor.REPORT_CSV);

        assertThatThrownBy(() -> reportExports.export(new ExportRequest("sales", Map.of("storeId", store.toString(),
                "from", "2026-11-01", "to", "2026-11-02", "groupBy", "day"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(failures(ExportMonitor.REPORT_CSV)).isEqualTo(before + 1);
        assertAlerted(ExportMonitor.REPORT_CSV);
    }

    @Test
    void aFailedInventoryExportIsCountedAndAlerted() {
        doThrow(new IllegalStateException("forced")).when(inventoryQueries).valuation(any());
        double before = failures(ExportMonitor.INVENTORY_CSV);

        assertThatThrownBy(() -> inventoryReports.csv("valuation", store, null, null))
                .hasMessageContaining("forced"); // translated by the repository proxy

        assertThat(failures(ExportMonitor.INVENTORY_CSV)).isEqualTo(before + 1);
        assertAlerted(ExportMonitor.INVENTORY_CSV);
    }

    @Test
    void aFailedAuditExportIsCountedAndAlerted() {
        AuditExport export = auditExports.start(new AuditFilter(store, clock.instant().minus(Duration.ofHours(1)),
                clock.instant(), null, null, null, null, null));
        double before = failures(ExportMonitor.AUDIT_CSV);

        assertThatThrownBy(() -> auditExports.writeCsv(export, new BrokenWriter()))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(failures(ExportMonitor.AUDIT_CSV)).isEqualTo(before + 1);
        assertAlerted(ExportMonitor.AUDIT_CSV);
    }

    private void assertAlerted(String kind) {
        assertThat(monitor.alerts()).filteredOn(alert -> alert.summary().contains(kind))
                .extracting(OperationalAlert::code, OperationalAlert::severity, OperationalAlert::storeId)
                .containsExactly(org.assertj.core.api.Assertions.tuple("EXPORT_FAILED", AlertSeverity.WARNING,
                        null));
    }

    private double failures(String kind) {
        Counter counter = meters.find("exports").tag("kind", kind).tag("outcome", "failure").counter();
        return counter == null ? 0 : counter.count();
    }

    /** A download whose connection broke. */
    static final class BrokenWriter extends Writer {

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            throw new IOException("connection reset");
        }

        @Override
        public void flush() throws IOException {
            throw new IOException("connection reset");
        }

        @Override
        public void close() {
        }
    }
}
