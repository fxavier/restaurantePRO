package com.xavier.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StoreScopes;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D82 / D171: "the voids of this store this week" is served by {@code audit_event_store_action_ix} also under
 * row-level security, as the application role in the auditor's store scope: the policy's filter sits on top, and the
 * store, action and time predicates (uuid, varchar and timestamptz equality and ranges are leakproof, D169) stay in the
 * index condition. The SQL is {@code AuditEventRepository.MATCHING} as Hibernate binds it for one store and one action
 * (the unused filters bound to null). Deterministic: a fixed seed of 12 stores × 8 actions × 150 rows, then
 * {@code ANALYZE} (as the owner) and {@code EXPLAIN} in one transaction with {@code enable_seqscan = off}.
 * {@link #MATCHING} is a hand copy of {@code AuditEventRepository.MATCHING} (JPQL), so keep the two in step. The seed is
 * append-only audit rows, dated 2019 so no other audit test's range sees them, left in the shared database.
 */
@SpringBootTest
@AutoConfigureMockMvc // the audit suite's one context
@Import(TestcontainersConfiguration.class)
class AuditPlanTest {

    static final String MATCHING = """
            SELECT e.id, e.occurred_at FROM audit.audit_event e
            WHERE e.occurred_at >= :from AND e.occurred_at < :to
              AND (:allStores = true OR e.store_id IN (:stores))
              AND (CAST(:actorId AS uuid) IS NULL OR e.actor_id = CAST(:actorId AS uuid))
              AND (CAST(:actorKind AS varchar) IS NULL OR e.actor_kind = CAST(:actorKind AS varchar))
              AND (CAST(:action AS varchar) IS NULL OR e.action = CAST(:action AS varchar))
              AND (CAST(:entityType AS varchar) IS NULL OR e.entity_type = CAST(:entityType AS varchar))
              AND (CAST(:entityId AS varchar) IS NULL OR e.entity_id = CAST(:entityId AS varchar))
            ORDER BY e.occurred_at DESC, e.id DESC LIMIT 50""";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    StoreScopes scopes;

    UUID store;
    String voided;

    @BeforeEach
    void twelveStoresOfEightActions() {
        List<UUID> stores = Stream.generate(UUID::randomUUID).limit(12).toList();
        store = stores.getFirst();
        String suffix = AuditFixtures.uniqueAction("PLAN");
        List<String> actions = Arrays.stream(new String[] {"LINE_VOIDED", "ORDER_OPENED", "BATCH_CONFIRMED",
                "PAYMENT_RECORDED", "DOCUMENT_ISSUED", "CASH_COUNTED", "PRICE_SET", "DISCOUNT_APPLIED"})
                .map(action -> action + "_" + suffix).toList();
        voided = actions.getFirst();
        for (UUID seeded : stores) {
            OwnerDatabase.shared().jdbc().sql("""
                    INSERT INTO audit.audit_event (id, occurred_at, actor_id, actor_kind, action, entity_type,
                        entity_id, store_id)
                    SELECT uuidv7(), CAST(:start AS timestamptz) + make_interval(mins => i), gen_random_uuid(), 'EMPLOYEE',
                           (ARRAY[:actions])[1 + i % 8], 'Order', gen_random_uuid()::text, :store
                    FROM generate_series(0, 1199) i""").param("start", Timestamp.from(Instant.parse(
                    "2019-01-01T00:00:00Z"))).param("actions", actions).param("store", seeded)
                    .update();
        }
    }

    @Test
    void theAuditOfOneStoreAndOneActionUsesTheStoreActionIndex() {
        String plan = scopes.callForStore(store, "plan-test", () -> tx.execute(status -> {
            OwnerDatabase.shared().jdbc().sql("ANALYZE audit.audit_event").update();
            assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                    .isEqualTo(TestcontainersConfiguration.APP_ROLE);
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("EXPLAIN " + MATCHING)
                    .param("from", Timestamp.from(Instant.parse("2019-01-01T00:00:00Z")))
                    .param("to", Timestamp.from(Instant.parse("2019-01-08T00:00:00Z")))
                    .param("allStores", false).param("stores", List.of(store)).param("actorId", null)
                    .param("actorKind", null).param("action", voided).param("entityType", null)
                    .param("entityId", null).query(String.class).list());
        }));

        assertThat(plan).containsPattern("(Index Scan( Backward)? using|Bitmap Index Scan on) "
                        + "audit_event_store_action_ix")
                .containsPattern("Index Cond: \\(\\(store_id = '" + store + "'::uuid\\) AND \\(\\(action\\)::text = '"
                        + voided + "'::text\\)");
    }
}
