package com.xavier.backend.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.reporting.ReportingModuleTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A range is intact (D86 finality) only when every FAILED rebuild overlapping it was followed by a COMPLETED rebuild
 * covering its part of the range: a later rebuild of a smaller part does not put the rest back.
 */
@ReportingModuleTest
class RebuildRunsTest {

    static final LocalDate D1 = LocalDate.of(2026, 3, 1);
    static final LocalDate D3 = LocalDate.of(2026, 3, 3);
    static final LocalDate D2 = LocalDate.of(2026, 3, 2);

    @Autowired
    RebuildRuns runs;

    @Autowired
    JdbcClient jdbc;

    UUID store;

    @BeforeEach
    void aStore() {
        store = UUID.randomUUID();
    }

    @Test
    void aFailedRebuildIsRepairedOnlyWhereALaterCompletedRebuildCoversIt() {
        run("FAILED", D1, D3, "2026-03-04T10:00:00Z");
        run("COMPLETED", D2, D2, "2026-03-04T11:00:00Z");

        assertThat(runs.rangeIntact(store, D1, D3)).isFalse();
        assertThat(runs.rangeIntact(store, D1, D1)).isFalse();
        assertThat(runs.rangeIntact(store, D2, D2)).isTrue();

        run("COMPLETED", D1, D3, "2026-03-04T12:00:00Z");
        assertThat(runs.rangeIntact(store, D1, D3)).isTrue();
    }

    @Test
    void aCompletedRebuildBeforeTheFailureRepairsNothing() {
        run("COMPLETED", D1, D3, "2026-03-04T09:00:00Z");
        run("FAILED", D1, D3, "2026-03-04T10:00:00Z");

        assertThat(runs.rangeIntact(store, D1, D3)).isFalse();
        assertThat(runs.rangeIntact(store, D3.plusDays(1), D3.plusDays(1))).isTrue();
    }

    private void run(String status, LocalDate from, LocalDate to, String startedAt) {
        jdbc.sql("""
                INSERT INTO reporting.rebuild_run (id, store_id, from_date, to_date, status, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz + interval '1 minute')""")
                .params(UUID.randomUUID(), store, from, to, status, startedAt, startedAt).update();
    }
}
