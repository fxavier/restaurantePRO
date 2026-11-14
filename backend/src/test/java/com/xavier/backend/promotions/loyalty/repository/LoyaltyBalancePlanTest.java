package com.xavier.backend.promotions.loyalty.repository;

import com.xavier.backend.OwnerDatabase;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.promotions.loyalty.LoyaltyModuleTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D110: the balance is Σ of the ledger, never stored, so it must be cheap: the repository's own SQL is served by
 * {@code loyalty_point_entry_customer_ix} (customer_id, occurred_at) INCLUDE (points). Deterministic: a fixed seed of
 * 2 000 members with 5 entries each, then {@code ANALYZE} (as the owner) and {@code EXPLAIN} in one transaction with
 * {@code enable_seqscan = off}, asserting the index node and its condition. The plan is the production one (D171): as
 * the application role, in a store scope (the ledger is company-wide, so no policy filters it).
 */
@LoyaltyModuleTest
class LoyaltyBalancePlanTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    StoreScopes scopes;

    @Test
    void theBalanceIsAnIndexScanOfTheMembersEntries() {
        seed();
        UUID member = jdbc.sql("""
                        SELECT customer_id FROM promotions.loyalty_point_entry WHERE reason = 'plan seed' LIMIT 1""")
                .query(UUID.class).single();

        String plan = scopes.callForStore(UUID.randomUUID(), "plan-test", () -> tx.execute(status -> {
            // ANALYZE needs the table owner: as the pool it is skipped with a warning (ADR 0039)
            OwnerDatabase.shared().jdbc().sql("ANALYZE promotions.loyalty_point_entry").update();
            assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                    .isEqualTo(TestcontainersConfiguration.APP_ROLE);
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            List<String> lines = jdbc.sql("EXPLAIN " + LoyaltyPointEntryRepository.BALANCE)
                    .param("customerId", member).query(String.class).list();
            return String.join("\n", lines);
        }));

        assertThat(plan).containsPattern("(Index Only Scan using|Index Scan using|Bitmap Index Scan on) "
                        + "loyalty_point_entry_customer_ix")
                .contains("Index Cond: (customer_id = '" + member + "'::uuid)");
    }

    private void seed() {
        jdbc.sql("""
                WITH seeded AS (
                    INSERT INTO promotions.loyalty_account (customer_id, status, enrolled_at, enrolled_by_actor_kind,
                        version)
                    SELECT uuidv7(), 'ACTIVE', now(), 'SYSTEM', 0 FROM generate_series(1, 2000)
                    RETURNING customer_id)
                INSERT INTO promotions.loyalty_point_entry (id, customer_id, kind, points, source_type, source_id,
                    reason, actor_kind, occurred_at)
                SELECT uuidv7(), s.customer_id, 'ADJUSTMENT', 10, 'ADJUSTMENT', uuidv7(), 'plan seed', 'SYSTEM', now()
                FROM seeded s CROSS JOIN generate_series(1, 5)""").update();
    }
}
