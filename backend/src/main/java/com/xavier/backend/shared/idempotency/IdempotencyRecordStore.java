package com.xavier.backend.shared.idempotency;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code shared.idempotency_record} access. Plain JDBC: it needs {@code ON CONFLICT} and {@code NOWAIT}. */
@Repository
class IdempotencyRecordStore {

    /**
     * What is stored for a key; {@code responseStatus}/{@code responseBody} are null while in progress, and
     * {@code responseLocation} is null when the response had no {@code Location} header. {@code leaseOwner} and
     * {@code leaseUntil} are set only for records claimed by {@code executeSteps} (D63).
     */
    record StoredRecord(String requestHash, boolean completed, Integer responseStatus, String responseBody,
                        String responseLocation, UUID leaseOwner, Instant leaseUntil) {

        boolean leased() {
            return leaseUntil != null;
        }

        /** A lease is live while {@code leaseUntil > now}; at or after {@code leaseUntil} it may be taken over. */
        boolean leaseLiveAt(Instant now) {
            return leaseUntil != null && leaseUntil.isAfter(now);
        }
    }

    private static final String SELECT = """
            SELECT request_hash, status, response_status, response_body::text AS response_body, response_location,
                   lease_owner, lease_until
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

    /**
     * D63 claim: like {@link #claim}, with the caller as lease owner until {@code leaseUntil}.
     *
     * @return true if this call created the record (and therefore owns its lease)
     */
    boolean claimWithLease(String scope, String key, String requestHash, UUID owner, Instant now,
                           Instant leaseUntil) {
        return jdbc.sql("""
                        INSERT INTO shared.idempotency_record
                            (scope, key, request_hash, status, created_at, lease_owner, lease_until)
                        VALUES (:scope, :key, :hash, 'IN_PROGRESS', :now, :owner, :until)
                        ON CONFLICT (scope, key) DO NOTHING
                        """)
                .param("scope", scope).param("key", key).param("hash", requestHash).param("now", Timestamp.from(now))
                .param("owner", owner).param("until", Timestamp.from(leaseUntil))
                .update() == 1;
    }

    /**
     * D63 takeover by compare-and-set: succeeds only for an unfinished claim whose lease has expired
     * ({@code lease_until <= now}), so of two concurrent takers exactly one wins.
     *
     * @return true if the caller now owns the lease
     */
    boolean takeOverExpired(String scope, String key, UUID owner, Instant now, Instant leaseUntil) {
        return jdbc.sql("""
                        UPDATE shared.idempotency_record SET lease_owner = :owner, lease_until = :until
                        WHERE scope = :scope AND key = :key AND status = 'IN_PROGRESS' AND lease_until <= :now
                        """)
                .param("owner", owner).param("until", Timestamp.from(leaseUntil)).param("now", Timestamp.from(now))
                .param("scope", scope).param("key", key)
                .update() == 1;
    }

    /**
     * D63 completion: stores the response only if the caller still owns the lease of the unfinished claim.
     *
     * @return false when the lease was lost (taken over, and possibly completed, by another request)
     */
    boolean completeOwned(String scope, String key, UUID owner, int responseStatus, String responseBody,
                          String responseLocation, Instant now) {
        return jdbc.sql("""
                        UPDATE shared.idempotency_record
                        SET status = 'COMPLETED', response_status = :status,
                            response_body = CAST(:body AS jsonb), response_location = :location, completed_at = :now
                        WHERE scope = :scope AND key = :key AND status = 'IN_PROGRESS' AND lease_owner = :owner
                        """)
                .param("status", responseStatus).param("body", responseBody).param("location", responseLocation)
                .param("now", Timestamp.from(now)).param("scope", scope).param("key", key).param("owner", owner)
                .update() == 1;
    }

    /** D63 failure: expires the caller's lease at once, so a retry with the same key runs the steps again. */
    void releaseOwned(String scope, String key, UUID owner, Instant now) {
        jdbc.sql("""
                        UPDATE shared.idempotency_record SET lease_until = :now
                        WHERE scope = :scope AND key = :key AND status = 'IN_PROGRESS' AND lease_owner = :owner
                        """)
                .param("now", Timestamp.from(now)).param("scope", scope).param("key", key).param("owner", owner)
                .update();
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

    /**
     * D47.4: completed records that completed before {@code cutoff}, and claims still {@code IN_PROGRESS} that were
     * created before it (abandoned: the owner crashed or its action was refused, and nobody retries that late). An
     * unfinished claim whose step lease is still live at {@code now} is kept: its owner is running (D63).
     *
     * @return the number of deleted records
     */
    int deleteExpiredBefore(Instant cutoff, Instant now) {
        return jdbc.sql("""
                        DELETE FROM shared.idempotency_record
                        WHERE (status = 'COMPLETED' AND completed_at < :cutoff)
                           OR (status = 'IN_PROGRESS' AND created_at < :cutoff
                               AND (lease_until IS NULL OR lease_until <= :now))
                        """)
                .param("cutoff", Timestamp.from(cutoff)).param("now", Timestamp.from(now))
                .update();
    }

    private StoredRecord map(java.sql.ResultSet rs, int row) throws SQLException {
        return new StoredRecord(
                rs.getString("request_hash"),
                "COMPLETED".equals(rs.getString("status")),
                (Integer) rs.getObject("response_status"),
                rs.getString("response_body"),
                rs.getString("response_location"),
                rs.getObject("lease_owner", UUID.class),
                rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant());
    }
}
