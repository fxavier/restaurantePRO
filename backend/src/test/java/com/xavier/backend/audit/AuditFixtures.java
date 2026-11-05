package com.xavier.backend.audit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.xavier.backend.access.Actor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Audit rows written straight into the table (inserting is what the append-only trigger allows), and request actors
 * with any mix of installation-wide and per-store grants. The database is shared by the whole test JVM, so every test
 * uses fresh stores and a fresh action name, and asserts only on those.
 */
final class AuditFixtures {

    private final JdbcClient jdbc;

    AuditFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A unique action name for one test, e.g. {@code LINE_VOIDED_1A2B3C4D}. */
    static String uniqueAction(String prefix) {
        byte[] bytes = new byte[4];
        ThreadLocalRandom.current().nextBytes(bytes);
        return prefix + "_" + HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
    }

    UUID row(UUID storeId, Instant at, String action) {
        return row(storeId, at, action, "Order", UUID.randomUUID().toString(), UUID.randomUUID(), "EMPLOYEE", null,
                "{}");
    }

    UUID row(UUID storeId, Instant at, String action, String entityType, String entityId, UUID actorId,
             String actorKind, String reason, String detailsJson) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO audit.audit_event (id, occurred_at, actor_id, actor_kind, action, entity_type,
                                                       entity_id, store_id, reason, details)
                        VALUES (:id, :at, :actor, :kind, :action, :type, :entity, :store, :reason, :details::jsonb)""")
                .param("id", id).param("at", Timestamp.from(at)).param("actor", actorId).param("kind", actorKind)
                .param("action", action).param("type", entityType).param("entity", entityId).param("store", storeId)
                .param("reason", reason).param("details", detailsJson).update();
        return id;
    }

    /** A BackOffice account holding exactly {@code authorities} ({@code Permission.authority(...)} strings). */
    static RequestPostProcessor account(String... authorities) {
        return as(Actor.account(UUID.randomUUID(), Set.of(authorities)));
    }

    static RequestPostProcessor as(Actor actor) {
        List<SimpleGrantedAuthority> granted = actor.authorities().stream().map(SimpleGrantedAuthority::new).toList();
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, granted));
    }

    static Set<String> authorities(String... authorities) {
        return Arrays.stream(authorities).collect(Collectors.toUnmodifiableSet());
    }
}
