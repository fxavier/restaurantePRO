package com.xavier.backend.promotions.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D110: the points balance is never stored. The account holds status and enrollment only; the balance is Σ of
 * {@code loyalty_point_entry}, served by the {@code (customer_id, occurred_at) INCLUDE (points)} index.
 */
@LoyaltyModuleTest
class LoyaltyNoBalanceColumnTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void theAccountHasNoBalanceColumn() {
        List<String> columns = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'promotions' AND table_name = 'loyalty_account' ORDER BY column_name""")
                .query(String.class).list();

        assertThat(columns).containsExactlyInAnyOrder("customer_id", "status", "suspended_reason", "enrolled_at",
                "enrolled_by_actor_kind", "enrolled_by_actor_id", "version");
    }

    @Test
    void noLoyaltyTableStoresABalance() {
        List<String> balanceLike = jdbc.sql("""
                        SELECT table_name || '.' || column_name FROM information_schema.columns
                        WHERE table_schema = 'promotions' AND table_name LIKE 'loyalty%'
                          AND (column_name LIKE '%balance%' OR column_name IN ('points_total', 'total_points'))""")
                .query(String.class).list();
        long ledgerTables = jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'promotions' AND table_name = 'loyalty_point_entry'""")
                .query(Long.class).single();

        assertThat(ledgerTables).isEqualTo(1);
        assertThat(balanceLike).isEmpty();
    }

    @Test
    void theBalanceIsAnIndexedSumOfTheLedger() {
        String index = jdbc.sql("""
                        SELECT indexdef FROM pg_indexes
                        WHERE schemaname = 'promotions' AND indexname = 'loyalty_point_entry_customer_ix'""")
                .query(String.class).single();

        assertThat(index).contains("(customer_id, occurred_at) INCLUDE (points)");
    }
}
