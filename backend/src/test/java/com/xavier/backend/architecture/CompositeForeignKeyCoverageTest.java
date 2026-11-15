package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.OwnerDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D179/D189 (security rule 3), per module until S76: foreign keys bypass row-level security, so every foreign key
 * between two TENANT* tables of a converted module pairs {@code tenant_id} with {@code tenant_id} (a child can never
 * reference another tenant's parent), and every business unique key includes {@code tenant_id} (two tenants can use the
 * same codes, and a violation cannot reveal another tenant's row). The primary key on {@code id} alone is not a
 * business key ({@code id} is a global UUIDv7). The modules are {@link RowLevelSecurityCoverageTest}'s.
 */
class CompositeForeignKeyCoverageTest {

    private static final String SCRATCH = "rls_scratch_composite";

    @Test
    void everyForeignKeyAndBusinessKeyOfTheConvertedModulesIncludesTheTenant() {
        for (String schema : RowLevelSecurityCoverageTest.TENANT_PROTECTED_MODULES) {
            assertModuleKeysIncludeTheTenant(schema);
        }
        for (String table : RowLevelSecurityCoverageTest.TENANT_PROTECTED_TABLES) {
            assertThat(keyProblems(table.substring(0, table.indexOf('.')), table)).as(table).isEmpty();
        }
    }

    /** The check is red-capable: a single-column foreign key and a business key without the tenant are reported. */
    @Test
    void aSingleColumnForeignKeyAndABusinessKeyWithoutTheTenantAreCaught() {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        drop(owner);
        try {
            OwnerDatabase.shared().tx(jdbc -> {
                jdbc.sql("CREATE SCHEMA " + SCRATCH).update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".parent (id uuid PRIMARY KEY, code text NOT NULL, "
                        + "tenant_id uuid NOT NULL DEFAULT shared.rls_tenant_id(), UNIQUE (tenant_id, id), "
                        + "CONSTRAINT parent_code_uk UNIQUE (tenant_id, code))").update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".child (id uuid PRIMARY KEY, parent_id uuid NOT NULL, "
                        + "tenant_id uuid NOT NULL DEFAULT shared.rls_tenant_id(), UNIQUE (tenant_id, id), "
                        + "CONSTRAINT child_parent_fk FOREIGN KEY (tenant_id, parent_id) "
                        + "REFERENCES " + SCRATCH + ".parent (tenant_id, id))").update();
                jdbc.sql("SELECT shared.rls_protect_tenant_table('" + SCRATCH + ".parent'), "
                        + "shared.rls_protect_tenant_table('" + SCRATCH + ".child')").query().listOfRows();
            });
            assertModuleKeysIncludeTheTenant(SCRATCH);

            owner.sql("ALTER TABLE " + SCRATCH + ".child ADD CONSTRAINT child_parent_only_fk FOREIGN KEY (parent_id) "
                    + "REFERENCES " + SCRATCH + ".parent (id)").update();
            assertThatThrownBy(() -> assertModuleKeysIncludeTheTenant(SCRATCH))
                    .hasMessageContaining("child_parent_only_fk");
            owner.sql("ALTER TABLE " + SCRATCH + ".child DROP CONSTRAINT child_parent_only_fk").update();

            owner.sql("ALTER TABLE " + SCRATCH + ".parent ADD CONSTRAINT parent_global_code_uk UNIQUE (code)")
                    .update();
            assertThatThrownBy(() -> assertModuleKeysIncludeTheTenant(SCRATCH))
                    .hasMessageContaining("parent_global_code_uk");
            owner.sql("ALTER TABLE " + SCRATCH + ".parent DROP CONSTRAINT parent_global_code_uk").update();

            owner.sql("CREATE UNIQUE INDEX parent_name_ux ON " + SCRATCH + ".parent (lower(code))").update();
            assertThatThrownBy(() -> assertModuleKeysIncludeTheTenant(SCRATCH)).hasMessageContaining("parent_name_ux");
        } finally {
            drop(owner);
        }
    }

    /** Every in-schema foreign key and unique key of the TENANT* tables of {@code schema} includes the tenant. */
    public static void assertModuleKeysIncludeTheTenant(String schema) {
        List<String> tables = OwnerDatabase.shared().jdbc().sql("""
                        SELECT r.table_name::text FROM shared.rls_tenant_table r
                            JOIN pg_catalog.pg_class c ON c.oid = r.table_name
                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = ? ORDER BY 1""").param(schema).query(String.class).list();
        List<String> problems = new ArrayList<>();
        tables.forEach(table -> problems.addAll(keyProblems(schema, table)));
        assertThat(problems).as("tenant keys of schema %s", schema).isEmpty();
    }

    private static List<String> keyProblems(String schema, String table) {
        JdbcClient jdbc = OwnerDatabase.shared().jdbc();
        List<String> problems = new ArrayList<>();
        // foreign keys to another TENANT* table: tenant_id must be paired with the parent's tenant_id
        List<Map<String, Object>> foreignKeys = jdbc.sql("""
                        SELECT k.conname,
                               EXISTS (SELECT 1 FROM unnest(k.conkey, k.confkey) AS pair(child, parent)
                                           JOIN pg_catalog.pg_attribute ca ON ca.attrelid = k.conrelid
                                               AND ca.attnum = pair.child
                                           JOIN pg_catalog.pg_attribute pa ON pa.attrelid = k.confrelid
                                               AND pa.attnum = pair.parent
                                       WHERE ca.attname = 'tenant_id' AND pa.attname = 'tenant_id') AS paired
                        FROM pg_catalog.pg_constraint k
                        WHERE k.conrelid = CAST(? AS regclass) AND k.contype = 'f'
                          AND EXISTS (SELECT 1 FROM shared.rls_tenant_table r WHERE r.table_name = k.confrelid)""")
                .param(table).query().listOfRows();
        for (Map<String, Object> key : foreignKeys) {
            if (!Boolean.TRUE.equals(key.get("paired"))) {
                problems.add(table + ": foreign key " + key.get("conname") + " does not pair tenant_id");
            }
        }
        // unique indexes (constraints and plain unique indexes) that are not the id primary key
        List<Map<String, Object>> uniques = jdbc.sql("""
                        SELECT ic.relname AS name,
                               i.indisprimary AND i.indnkeyatts = 1
                                   AND pg_catalog.pg_get_indexdef(i.indexrelid, 1, true) = 'id' AS id_key,
                               EXISTS (SELECT 1 FROM generate_series(1, i.indnkeyatts) AS position(n)
                                       WHERE pg_catalog.pg_get_indexdef(i.indexrelid, position.n, true) = 'tenant_id')
                                   AS with_tenant
                        FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
                        WHERE i.indrelid = CAST(? AS regclass) AND i.indisunique""")
                .param(table).query().listOfRows();
        for (Map<String, Object> unique : uniques) {
            if (!Boolean.TRUE.equals(unique.get("id_key")) && !Boolean.TRUE.equals(unique.get("with_tenant"))) {
                problems.add(table + ": unique key " + unique.get("name") + " (schema " + schema
                        + ") does not include tenant_id");
            }
        }
        return problems;
    }

    private static void drop(JdbcClient owner) {
        owner.sql("DELETE FROM shared.rls_protected_table WHERE table_name::text LIKE ?").param(SCRATCH + ".%")
                .update();
        owner.sql("DELETE FROM shared.rls_tenant_table WHERE table_name::text LIKE ?").param(SCRATCH + ".%")
                .update();
        owner.sql("DROP SCHEMA IF EXISTS " + SCRATCH + " CASCADE").update();
    }
}
