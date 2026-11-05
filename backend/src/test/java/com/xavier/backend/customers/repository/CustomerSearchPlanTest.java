package com.xavier.backend.customers.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.customers.CustomersModuleTest;
import java.util.List;
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
 */
@CustomersModuleTest
class CustomerSearchPlanTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void fiveThousandCustomers() {
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
        assertThat(indexDefinition("customer_tax_id_ix")).contains("(tax_id)");
    }

    @Test
    void aPhoneSuffixIsAPrefixRangeOnTheReversedDigitsAndTheTaxIdUsesItsIndex() {
        String plan = explain(CustomerRepository.SEARCH_BY_PHONE_SUFFIX, "reversedPrefix", "7654%", "taxId", "4567");

        assertThat(plan).containsPattern("(Index Scan using|Index Only Scan using|Bitmap Index Scan on) "
                        + "customer_phone_digits_reversed_ix")
                .contains("Index Cond: ((digits_reversed ~>=~ '7654'::text) AND (digits_reversed ~<~ '7655'::text))")
                .containsPattern("(Index Scan using|Index Only Scan using|Bitmap Index Scan on) customer_tax_id_ix");
    }

    @Test
    void aNamePartIsATrigramIndexScanAndTheTaxIdUsesItsIndex() {
        String plan = explain(CustomerRepository.SEARCH_BY_NAME, "namePart", "a3f9c", "taxId", "a3f9c");

        assertThat(plan).contains("Bitmap Index Scan on customer_name_search_trgm_ix")
                .contains("Index Cond: (name_search ~~ '%a3f9c%'::text)")
                .containsPattern("(Index Scan using|Bitmap Index Scan on) customer_tax_id_ix");
    }

    private String explain(String sql, String firstName, String first, String secondName, String second) {
        return tx.execute(status -> {
            jdbc.sql("ANALYZE customers.customer").update();
            jdbc.sql("ANALYZE customers.customer_phone").update();
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            List<String> lines = jdbc.sql("EXPLAIN " + sql).param(firstName, first).param(secondName, second)
                    .param("limit", 20).query(String.class).list();
            return String.join("\n", lines);
        });
    }

    private String indexDefinition(String name) {
        return jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'customers' AND indexname = ?")
                .param(name).query(String.class).single();
    }
}
