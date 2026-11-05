package com.xavier.backend.reporting.repository;

import com.xavier.backend.reporting.model.dto.RebuildView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The rebuild runs; {@code rebuild_run_running_uk} allows one RUNNING run per store. */
@Repository
public class RebuildRuns {

    public static final String ONE_RUNNING_PER_STORE = "rebuild_run_running_uk";
    private static final int MAX_ERROR = 500;

    private final JdbcClient jdbc;

    RebuildRuns(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws org.springframework.dao.DuplicateKeyException on {@link #ONE_RUNNING_PER_STORE} */
    public void start(UUID id, UUID storeId, LocalDate from, LocalDate to, Instant at) {
        jdbc.sql("""
                        INSERT INTO reporting.rebuild_run (id, store_id, from_date, to_date, status, started_at)
                        VALUES (?, ?, ?, ?, 'RUNNING', ?)""")
                .params(id, storeId, from, to, Timestamp.from(at)).update();
    }

    public void progress(UUID id, long projected) {
        jdbc.sql("UPDATE reporting.rebuild_run SET projected = ? WHERE id = ? AND status = 'RUNNING'")
                .params(projected, id).update();
    }

    public void finish(UUID id, long projected, String error, Instant at) {
        jdbc.sql("""
                        UPDATE reporting.rebuild_run SET status = ?, projected = ?, error = ?, finished_at = ?
                        WHERE id = ? AND status = 'RUNNING'""")
                .params(error == null ? "COMPLETED" : "FAILED", projected, truncated(error), Timestamp.from(at), id)
                .update();
    }

    /** Marks every RUNNING run FAILED: a restart interrupted it. @return how many */
    public int abandonRunning(String error, Instant at) {
        return jdbc.sql("""
                        UPDATE reporting.rebuild_run SET status = 'FAILED', error = ?, finished_at = ?
                        WHERE status = 'RUNNING'""")
                .params(error, Timestamp.from(at)).update();
    }

    /**
     * Whether the store's projections in {@code [from, to]} are as complete as the live ones: no rebuild overlapping
     * the range is RUNNING, and every FAILED one (it deleted rows it never put back) was followed by a COMPLETED
     * rebuild covering its part of the range.
     */
    public boolean rangeIntact(UUID storeId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT NOT EXISTS (
                            SELECT 1 FROM reporting.rebuild_run r
                            WHERE r.store_id = :store AND r.from_date <= :to AND r.to_date >= :from
                              AND (r.status = 'RUNNING' OR (r.status = 'FAILED' AND NOT EXISTS (
                                  SELECT 1 FROM reporting.rebuild_run c
                                  WHERE c.store_id = r.store_id AND c.status = 'COMPLETED'
                                    AND c.started_at > r.started_at
                                    AND c.from_date <= greatest(r.from_date, :from)
                                    AND c.to_date >= least(r.to_date, :to)))))""")
                .param("store", storeId).param("from", from).param("to", to).query(Boolean.class).single();
    }

    public Optional<RebuildView> find(UUID id) {
        return jdbc.sql("SELECT * FROM reporting.rebuild_run WHERE id = ?").param(id).query(RebuildRuns::view)
                .optional();
    }

    public Optional<RebuildView> last(UUID storeId) {
        return jdbc.sql("""
                        SELECT * FROM reporting.rebuild_run WHERE store_id = ?
                        ORDER BY started_at DESC, id DESC LIMIT 1""")
                .param(storeId).query(RebuildRuns::view).optional();
    }

    private static String truncated(String error) {
        return error == null || error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
    }

    private static RebuildView view(ResultSet rs, int row) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new RebuildView(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("from_date", LocalDate.class), rs.getObject("to_date", LocalDate.class),
                rs.getString("status"), rs.getLong("projected"), rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(), rs.getString("error"));
    }
}
