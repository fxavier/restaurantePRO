package com.xavier.backend.shared.idempotency;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code shared.idempotency_record} access. Plain JDBC: it needs {@code ON CONFLICT} and {@code NOWAIT}. */
@Repository
class IdempotencyRecordStore {

    /**
     * What is stored for a key; {@code responseStatus}/{@code responseBody} are null while in progress, and
     * {@code responseLocation} is null when the response had no {@code Location} header.
     */
    record StoredRecord(String requestHash, boolean completed, Integer responseStatus, String responseBody,
                        String responseLocation) {
    }

    private static final String SELECT = """
            SELECT request_hash, status, response_status, response_body::text AS response_body, response_location
            FROM shared.idempotency_record WHERE scope = :scope AND key = :key
            """;

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;

    IdempotencyRecordStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Makes sure a record exists. @return true if this call created it. */
    boolean claim(String scope, String key, String requestHash, Instant now) {
        return jdbc.sql("""
                        INSERT INTO shared.idempotency_record (scope, key, request_hash, status, created_at)
                        VALUES (:scope, :key, :hash, 'IN_PROGRESS', :now)
                        ON CONFLICT (scope, key) DO NOTHING
                        """)
                .param("scope", scope).param("key", key).param("hash", requestHash).param("now", Timestamp.from(now))
                .update() == 1;
    }

    Optional<StoredRecord> find(String scope, String key) {
        return jdbc.sql(SELECT).param("scope", scope).param("key", key).query(this::map).optional();
    }

    /**
     * Locks the record for the rest of the current transaction without waiting.
     *
     * @return the record, or empty if another live transaction holds the lock (its owner is still running)
     */
    Optional<StoredRecord> lockWithoutWaiting(String scope, String key) {
        try {
            return Optional.of(jdbc.sql(SELECT + " FOR UPDATE NOWAIT")
                    .param("scope", scope).param("key", key).query(this::map).single());
        } catch (DataAccessException e) {
            if (isLockNotAvailable(e)) {
                return Optional.empty(); // the lock is held by a live owner
            }
            throw e;
        }
    }

    /**
     * PostgreSQL SQLSTATE 55P03 (lock_not_available) only. Spring puts deadlock (40P01) and serialization
     * failure (40001) in the same {@code PessimisticLockingFailureException} family; those are not "another
     * request holds the key" and must propagate instead of becoming a false 409.
     */
    static boolean isLockNotAvailable(DataAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    void complete(String scope, String key, int responseStatus, String responseBody, String responseLocation,
                  Instant now) {
        jdbc.sql("""
                        UPDATE shared.idempotency_record
                        SET status = 'COMPLETED', response_status = :status,
                            response_body = CAST(:body AS jsonb), response_location = :location, completed_at = :now
                        WHERE scope = :scope AND key = :key AND status = 'IN_PROGRESS'
                        """)
                .param("status", responseStatus).param("body", responseBody).param("location", responseLocation)
                .param("now", Timestamp.from(now)).param("scope", scope).param("key", key)
                .update();
    }

    /** D47.4. @return the number of deleted records */
    int deleteCompletedBefore(Instant cutoff) {
        return jdbc.sql("""
                        DELETE FROM shared.idempotency_record
                        WHERE status = 'COMPLETED' AND completed_at < :cutoff
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    private StoredRecord map(java.sql.ResultSet rs, int row) throws SQLException {
        return new StoredRecord(
                rs.getString("request_hash"),
                "COMPLETED".equals(rs.getString("status")),
                (Integer) rs.getObject("response_status"),
                rs.getString("response_body"),
                rs.getString("response_location"));
    }
}
