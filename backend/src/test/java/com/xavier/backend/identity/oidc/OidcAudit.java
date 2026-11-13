package com.xavier.backend.identity.oidc;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the OIDC audit rows a test caused (the database is shared: always filter by something the test owns). */
final class OidcAudit {

    private OidcAudit() {
    }

    static List<String> details(JdbcClient jdbc, String action, String entityId) {
        return jdbc.sql("SELECT details::text FROM audit.audit_event WHERE action = ? AND entity_id = ? "
                        + "ORDER BY occurred_at").param(action).param(entityId).query(String.class).list();
    }

    static long count(JdbcClient jdbc, String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = ?").param(action)
                .query(Long.class).single();
    }
}
