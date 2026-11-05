package com.xavier.backend.customers.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.customers.CustomersModuleTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The search queries are served by their indexes (§8 "plano verificado", D72, A31), not by a sequential scan:
 * {@code EXPLAIN} of the repository's own SQL, with bound parameters, after {@code ANALYZE} on 5 000 seeded
 * customers with a phone each.
 */
@CustomersModuleTest
class CustomerSearchPlanTest {

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void fiveThousandCustomers() {
        if (jdbc.sql("SELECT count(*) FROM customers.customer WHERE name LIKE 'Seed %'").query(Long.class)
                .single() < 5000) {
            jdbc.sql("""
                    INSERT INTO customers.customer (id, name, tax_id, active, created_at, updated_at, version)
                    SELECT uuidv7(), 'Seed ' || md5(i::text), 'SEED' || i, true, now(), now(), 0
                    FROM generate_series(1, 5000) i""").update();
            jdbc.sql("""
                    INSERT INTO customers.customer_phone (id, customer_id, digits, is_primary)
                    SELECT uuidv7(), c.id, lpad((floor(random() * 1e9))::bigint::text, 9, '0'), true
                    FROM customers.customer c
                    WHERE c.name LIKE 'Seed %'
                      AND NOT EXISTS (SELECT 1 FROM customers.customer_phone p WHERE p.customer_id = c.id)
                    """).update();
        }
        jdbc.sql("ANALYZE customers.customer").update();
        jdbc.sql("ANALYZE customers.customer_phone").update();
    }

    @Test
    void aPhoneSuffixIsAPrefixScanOfTheReversedDigitsAndTheTaxIdUsesItsIndex() {
        String plan = explain(CustomerRepository.SEARCH_BY_PHONE_SUFFIX,
                "reversedPrefix", "7654%", "taxId", "4567");

        assertThat(plan).contains("customer_phone_digits_reversed_ix").contains("customer_tax_id_ix")
                .doesNotContain("Seq Scan");
    }

    @Test
    void aNamePartUsesTheTrigramIndexAndTheTaxIdUsesItsIndex() {
        String plan = explain(CustomerRepository.SEARCH_BY_NAME, "namePart", "a3f9c", "taxId", "a3f9c");

        assertThat(plan).contains("customer_name_search_trgm_ix").contains("customer_tax_id_ix")
                .doesNotContain("Seq Scan");
    }

    private String explain(String sql, String firstName, String first, String secondName, String second) {
        List<String> lines = jdbc.sql("EXPLAIN " + sql).param(firstName, first).param(secondName, second)
                .param("limit", 20).query(String.class).list();
        return String.join("\n", lines);
    }
}
