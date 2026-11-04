package com.xavier.backend.shared.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code shared.idempotency_record} access. Plain JDBC: the claim needs {@code ON CONFLICT DO NOTHING}. */
@Repository
class IdempotencyRecordStore {

    /** What is stored for a key; {@code responseStatus}/{@code responseBody} are null while in progress. */
    record StoredRecord(String requestHash, boolean completed, Integer responseStatus, String responseBody) {
    }

    private final JdbcClient jdbc;

    IdempotencyRecordStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this call claimed the key; false if a record already existed. */
    boolean claim(String scope, String key, String requestHash, Instant now) {
        return jdbc.sql("""
                        INSERT INTO shared.idempotency_record (scope, key, request_hash, status, created_at)
                        VALUES (:scope, :key, :hash, 'IN_PROGRESS', :now)
                        ON CONFLICT (scope, key) DO NOTHING
                        """)
                .param("scope", scope).param("key", key).param("hash", requestHash).param("now", ts(now))
                .update() == 1;
    }

    Optional<StoredRecord> find(String scope, String key) {
        return jdbc.sql("""
                        SELECT request_hash, status, response_status, response_body::text AS response_body
                        FROM shared.idempotency_record WHERE scope = :scope AND key = :key
                        """)
                .param("scope", scope).param("key", key)
                .query((rs, i) -> new StoredRecord(
                        rs.getString("request_hash"),
                        "COMPLETED".equals(rs.getString("status")),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body")))
                .optional();
    }

    void complete(String scope, String key, int responseStatus, String responseBody, Instant now) {
        jdbc.sql("""
                        UPDATE shared.idempotency_record
                        SET status = 'COMPLETED', response_status = :status,
                            response_body = CAST(:body AS jsonb), completed_at = :now
                        WHERE scope = :scope AND key = :key AND status = 'IN_PROGRESS'
                        """)
                .param("status", responseStatus).param("body", responseBody).param("now", ts(now))
                .param("scope", scope).param("key", key)
                .update();
    }

    void release(String scope, String key) {
        jdbc.sql("DELETE FROM shared.idempotency_record WHERE scope = :scope AND key = :key AND status = 'IN_PROGRESS'")
                .param("scope", scope).param("key", key)
                .update();
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }
}
