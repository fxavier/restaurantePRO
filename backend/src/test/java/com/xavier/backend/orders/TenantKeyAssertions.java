package com.xavier.backend.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import java.util.ArrayList;
import java.util.List;

/**
 * The composite-key rule of D180 for the orders, dining and production schemas (S74a): every in-schema foreign key
 * between tenant tables pairs {@code tenant_id} with the parent's {@code tenant_id}, and every unique key other than
 * the {@code id} primary key includes {@code tenant_id}. Same queries as {@code CompositeForeignKeyCoverageTest},
 * which lists its converted modules in shared test support; S76 registers these three modules there and this class
 * is then deleted.
 */
public final class TenantKeyAssertions {

    private TenantKeyAssertions() {
    }

    public static void assertModuleKeysIncludeTheTenant(String schema) {
        List<String> problems = new ArrayList<>();
        List<String> tables = OwnerDatabase.shared().jdbc().sql("""
                        SELECT r.table_name::text FROM shared.rls_tenant_table r
                            JOIN pg_catalog.pg_class c ON c.oid = r.table_name
                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = ? ORDER BY 1""").param(schema).query(String.class).list();
        assertThat(tables).as("tenant tables of %s", schema).isNotEmpty();
        for (String table : tables) {
            OwnerDatabase.shared().jdbc().sql("""
                            SELECT k.conname FROM pg_catalog.pg_constraint k
                            WHERE k.conrelid = CAST(? AS regclass) AND k.contype = 'f'
                              AND EXISTS (SELECT 1 FROM shared.rls_tenant_table r WHERE r.table_name = k.confrelid)
                              AND NOT EXISTS (SELECT 1 FROM unnest(k.conkey, k.confkey) AS pair(child, parent)
                                                  JOIN pg_catalog.pg_attribute ca ON ca.attrelid = k.conrelid
                                                      AND ca.attnum = pair.child
                                                  JOIN pg_catalog.pg_attribute pa ON pa.attrelid = k.confrelid
                                                      AND pa.attnum = pair.parent
                                              WHERE ca.attname = 'tenant_id' AND pa.attname = 'tenant_id')""")
                    .param(table).query(String.class).list()
                    .forEach(name -> problems.add(table + ": foreign key " + name + " does not pair tenant_id"));
            OwnerDatabase.shared().jdbc().sql("""
                            SELECT ic.relname FROM pg_catalog.pg_index i
                                JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
                            WHERE i.indrelid = CAST(? AS regclass) AND i.indisunique
                              AND NOT (i.indisprimary AND i.indnkeyatts = 1
                                       AND pg_catalog.pg_get_indexdef(i.indexrelid, 1, true) = 'id')
                              AND NOT EXISTS (SELECT 1 FROM generate_series(1, i.indnkeyatts) AS position(n)
                                              WHERE pg_catalog.pg_get_indexdef(i.indexrelid, position.n, true)
                                                    = 'tenant_id')""")
                    .param(table).query(String.class).list()
                    .forEach(name -> problems.add(table + ": unique key " + name + " does not include tenant_id"));
        }
        assertThat(problems).as("tenant keys of schema %s", schema).isEmpty();
    }
}
