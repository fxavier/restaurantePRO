package com.xavier.backend.customers.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.customers.CustomersModuleTest;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The search queries can be served by their indexes (§8 "plano verificado", D72, A31). Deterministic, so it does not
 * depend on the planner statistics of a shared database: a fixed seed of 5 000 customers with a phone each, then
 * {@code ANALYZE} and {@code EXPLAIN} of the repository's own SQL (bound parameters) in one transaction with
 * {@code enable_seqscan = off}. The assertions name the index node and its index condition, so a predicate the
 * index cannot serve (e.g. {@code reverse(digits) LIKE …}) fails. A stats-free check pins the operator classes.
 *
 * <p>R6 (D189, S74c): the same seed in tenants A and B (identical tax ids and phones), explained in tenant A, so the
 * restrictive {@code tenant_scope} policy is part of every plan and the tax id index leads with the tenant.
 */
@CustomersModuleTest
class CustomerSearchPlanTest {

    /** The tenant policy's InitPlan as {@code EXPLAIN} prints it: an index condition that leads the key. */
    private static final String TENANT = "\\(tenant_id = \\(InitPlan \\d+\\)\\.col1\\)";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TenantScopes tenants;

    private static boolean seeded;

    /** Once per JVM: a second seed only grows the tables (and the 15 s statement timeout of the pool role). */
    @BeforeEach
    void fiveThousandCustomersInEachOfTwoTenants() {
        synchronized (CustomerSearchPlanTest.class) {
            if (!seeded) {
                seed(TenantTestSupport.B);
                seed(TenantTestSupport.A);
                seeded = true;
            }
        }
    }

    /** As the application role in {@code tenant}: its policy hides the other tenant's rows from the NOT EXISTS. */
    private static void seed(UUID tenant) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            seedWith(app.jdbc());
        }
    }

    private static void seedWith(JdbcClient jdbc) {
        jdbc.sql("""
                INSERT INTO customers.customer (id, name, tax_id, active, created_at, updated_at, version)
                SELECT uuidv7(), 'Seed ' || md5(i::text), 'SEED' || i, true, now(), now(), 0
                FROM generate_series(1, 5000) i""").update();
        jdbc.sql("""
                INSERT INTO customers.customer_phone (id, customer_id, digits, is_primary)
                SELECT uuidv7(), c.id, lpad(((substr(c.tax_id, 5)::bigint * 7919) % 1000000000)::text, 9, '0'), true
                FROM customers.customer c
                WHERE c.tax_id LIKE 'SEED%'
                  AND NOT EXISTS (SELECT 1 FROM customers.customer_phone p WHERE p.customer_id = c.id)""").update();
    }

    @Test
    void theIndexesHaveTheOperatorClassesTheSearchNeeds() {
        assertThat(indexDefinition("customer_name_search_trgm_ix")).contains("USING gin (name_search gin_trgm_ops)");
        assertThat(indexDefinition("customer_phone_digits_reversed_ix")).contains("(digits_reversed text_pattern_ops)");
        assertThat(indexDefinition("customer_tax_id_ix")).contains("(tenant_id, tax_id)");
    }

    @Test
    void aPhoneSuffixIsAPrefixRangeOnTheReversedDigitsAndTheTaxIdUsesItsIndex() {
        String plan = explain(CustomerRepository.SEARCH_BY_PHONE_SUFFIX, Map.of("reversedFrom", "7654",
                "reversedBefore", "7655", "taxId", "4567"));

        assertThat(plan).containsPattern("(Index Scan using|Index Only Scan using|Bitmap Index Scan on) "
                        + "customer_phone_digits_reversed_ix")
                .contains("Index Cond: ((digits_reversed ~>=~ '7654'::text) AND (digits_reversed ~<~ '7655'::text))")
                .containsPattern("(Index Scan using|Index Only Scan using|Bitmap Index Scan on) customer_tax_id_ix")
                .containsPattern("Index Cond: \\(" + TENANT + " AND \\(\\(tax_id\\)::text = '4567'::text\\)\\)");
    }

    /**
     * D169: {@code LIKE} is not leakproof, so under row-level security the trigram index cannot serve the name part:
     * the tenant policy's {@code tenant_id} index reads this tenant's customers and {@code LIKE} filters them (a
     * company's customers, not the platform's, which the tenant condition proves). The OR with the exact tax id is
     * then a filter too (follow-up if a tenant outgrows it). A change that made {@code LIKE} index-served again would have to prove it leaks nothing.
     */
    @Test
    void aNamePartFiltersTheTenantsCustomers() {
        String plan = explain(CustomerRepository.SEARCH_BY_NAME, Map.of("namePart", "a3f9c", "taxId", "a3f9c"));

        assertThat(plan).doesNotContain("customer_name_search_trgm_ix")
                .containsPattern("Index Cond: " + TENANT)
                .contains("name_search ~~ '%a3f9c%'::text");
    }

    private String explain(String sql, Map<String, Object> params) {
        return TenantTestSupport.inTenant(tenants, TenantTestSupport.A, () -> tx.execute(status -> {
            // ANALYZE needs the table owner: as the pool it is skipped with a warning (ADR 0039)
            OwnerDatabase.shared().jdbc().sql("ANALYZE customers.customer").update();
            OwnerDatabase.shared().jdbc().sql("ANALYZE customers.customer_phone").update();
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            List<String> lines = jdbc.sql("EXPLAIN " + sql).params(params).param("limit", 20).query(String.class)
                    .list();
            return String.join("\n", lines);
        }));
    }

    private String indexDefinition(String name) {
        return jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'customers' AND indexname = ?")
                .param(name).query(String.class).single();
    }
}
