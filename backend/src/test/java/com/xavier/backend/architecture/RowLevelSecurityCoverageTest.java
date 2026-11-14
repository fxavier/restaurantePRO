package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D167/D171 coverage against the real migrations: every table of a module schema is either protected by the V1008
 * helpers (ENABLE + FORCE, the one canonical policy, the {@code RS002} trigger) or registered UNSCOPED with a reason;
 * no {@code SECURITY DEFINER} function outside the allow-list; the application role is bound by row-level security.
 * Each policy slice asserts its own schemas with {@link #assertModuleClassified}; S70 adds the all-schemas assertion.
 */
public class RowLevelSecurityCoverageTest {

    /** D168: the only definer functions; each reads one protected table's store columns, nothing else. */
    static final Set<String> SECURITY_DEFINER_ALLOWED = Set.of("shared.store_of(regclass,uuid)",
            "inventory.stock_transfer_stores(uuid)");

    private static final String SCRATCH = "rls_scratch_coverage";

    @Test
    void theSchemasOfThePrefactorAreClassified() {
        for (String schema : List.of("shared", "public", "organization", "identity", "customers")) {
            assertModuleClassified(schema);
        }
    }

    @Test
    void noSecurityDefinerFunctionOutsideTheAllowList() {
        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT p.oid::regprocedure::text FROM pg_catalog.pg_proc p
                            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                        WHERE p.prosecdef AND n.nspname NOT IN ('pg_catalog', 'information_schema')""")
                .query(String.class).list()).isSubsetOf(SECURITY_DEFINER_ALLOWED).contains("shared.store_of(regclass,uuid)");
    }

    @Test
    void theApplicationRoleIsBoundByRowLevelSecurity() {
        JdbcClient jdbc = OwnerDatabase.shared().jdbc();
        Map<String, Object> role = jdbc.sql("SELECT rolsuper, rolbypassrls, rolinherit FROM pg_catalog.pg_roles "
                + "WHERE rolname = ?").param(TestcontainersConfiguration.APP_ROLE).query().singleRow();

        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false)
                .containsEntry("rolinherit", false);
        assertThat(jdbc.sql("""
                        SELECT (SELECT count(*) FROM pg_catalog.pg_class WHERE relowner = r.oid)
                             + (SELECT count(*) FROM pg_catalog.pg_proc WHERE proowner = r.oid)
                             + (SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspowner = r.oid)
                        FROM pg_catalog.pg_roles r WHERE r.rolname = ?""")
                .param(TestcontainersConfiguration.APP_ROLE).query(Long.class).single()).as("owns nothing").isZero();
        assertThat(jdbc.sql("""
                        SELECT c.oid::regclass::text FROM pg_catalog.pg_class c
                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                          AND n.nspname NOT LIKE 'pg\\_%'
                          AND (has_table_privilege(:role, c.oid, 'TRUNCATE')
                               OR has_table_privilege(:role, c.oid, 'REFERENCES')
                               OR has_table_privilege(:role, c.oid, 'TRIGGER')
                               OR has_table_privilege(:role, c.oid, 'MAINTAIN'))""")
                .param("role", TestcontainersConfiguration.APP_ROLE).query(String.class).list())
                .as("no TRUNCATE, REFERENCES, TRIGGER or MAINTAIN").isEmpty();
    }

    /** The helper is red-capable: an unclassified table, a table no longer forced and a hand-written policy fail. */
    @Test
    void theHelperCatchesAnUnclassifiedTableAMissingForceAndANonCanonicalPolicy() {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        dropScratch(owner);
        try {
            OwnerDatabase.shared().tx(jdbc -> {
                jdbc.sql("CREATE SCHEMA " + SCRATCH).update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".parent (id uuid PRIMARY KEY, store_id uuid NOT NULL)").update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".page (id uuid PRIMARY KEY, store_id uuid)").update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".child (id uuid PRIMARY KEY, parent_id uuid NOT NULL "
                        + "REFERENCES " + SCRATCH + ".parent (id))").update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".company (id uuid PRIMARY KEY)").update();
                jdbc.sql("SELECT shared.rls_protect_store_table('" + SCRATCH + ".parent'), "
                        + "shared.rls_protect_store_or_all_table('" + SCRATCH + ".page'), "
                        + "shared.rls_protect_child_table('" + SCRATCH + ".child', 'parent_id', '" + SCRATCH
                        + ".parent', 'id'), shared.rls_register_unscoped('" + SCRATCH + ".company', 'company-wide')")
                        .query().listOfRows();
            });
            assertModuleClassified(SCRATCH);

            owner.sql("CREATE TABLE " + SCRATCH + ".stray (id uuid PRIMARY KEY, store_id uuid)").update();
            assertThatThrownBy(() -> assertModuleClassified(SCRATCH)).hasMessageContaining(SCRATCH + ".stray");
            owner.sql("DROP TABLE " + SCRATCH + ".stray").update();

            owner.sql("ALTER TABLE " + SCRATCH + ".child NO FORCE ROW LEVEL SECURITY").update();
            assertThatThrownBy(() -> assertModuleClassified(SCRATCH)).hasMessageContaining(SCRATCH + ".child");
            owner.sql("ALTER TABLE " + SCRATCH + ".child FORCE ROW LEVEL SECURITY").update();

            owner.sql("DROP POLICY store_or_all_scope ON " + SCRATCH + ".page").update();
            owner.sql("CREATE POLICY store_or_all_scope ON " + SCRATCH + ".page USING (true)").update();
            assertThatThrownBy(() -> assertModuleClassified(SCRATCH)).hasMessageContaining(SCRATCH + ".page");
        } finally {
            dropScratch(owner);
        }
    }

    /**
     * Every table of {@code schema} is classified (D167): registered in {@code shared.rls_unscoped_table}, or protected
     * with ENABLE + FORCE, exactly one canonical policy ({@code store_scope}, {@code store_or_all_scope} or
     * {@code parent_scope}, compared with the same V1008 helper applied to a copy) and its {@code RS002} trigger.
     * {@code specialTables} (D168: {@code audit_event}, {@code stock_transfer}, {@code warehouse}) need only ENABLE,
     * FORCE and a policy; their own slice proves their policies.
     */
    public static void assertModuleClassified(String schema, String... specialTables) {
        Set<String> special = Set.of(specialTables);
        List<String> problems = new ArrayList<>();
        JdbcClient jdbc = OwnerDatabase.shared().jdbc();
        List<Map<String, Object>> tables = jdbc.sql("""
                        SELECT c.oid, c.oid::regclass::text AS name, c.relname, c.relrowsecurity AS enabled,
                               c.relforcerowsecurity AS forced,
                               EXISTS (SELECT 1 FROM shared.rls_unscoped_table u WHERE u.table_name = c.oid)
                                   AS registered
                        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
                        ORDER BY c.relname""")
                .param(schema).query().listOfRows();
        assertThat(tables).as("schema %s has tables", schema).isNotEmpty();
        for (Map<String, Object> table : tables) {
            String name = (String) table.get("name");
            if (Boolean.TRUE.equals(table.get("registered"))) {
                continue;
            }
            if (!Boolean.TRUE.equals(table.get("enabled")) || !Boolean.TRUE.equals(table.get("forced"))) {
                problems.add(name + ": neither registered UNSCOPED nor protected (ENABLE + FORCE)");
                continue;
            }
            List<Map<String, Object>> policies = policies(jdbc, ((Number) table.get("oid")).longValue());
            if (special.contains((String) table.get("relname"))) {
                if (policies.isEmpty()) {
                    problems.add(name + ": a special table without a policy");
                }
                continue;
            }
            problems.addAll(canonicalProblems(name, (String) table.get("relname"),
                    ((Number) table.get("oid")).longValue(), policies));
        }
        assertThat(problems).as("row-level security coverage of schema %s", schema).isEmpty();
    }

    private static List<String> canonicalProblems(String name, String relname, long oid,
                                                  List<Map<String, Object>> policies) {
        if (policies.size() != 1) {
            return List.of(name + ": expected one canonical policy, found " + policies.size());
        }
        String policy = (String) policies.getFirst().get("polname");
        JdbcClient jdbc = OwnerDatabase.shared().jdbc();
        String helper;
        String guardedColumn;
        switch (policy) {
            case "store_scope" -> {
                helper = "shared.rls_protect_store_table('pg_temp." + relname + "')";
                guardedColumn = "store_id";
            }
            case "store_or_all_scope" -> {
                helper = "shared.rls_protect_store_or_all_table('pg_temp." + relname + "')";
                guardedColumn = "store_id";
            }
            case "parent_scope" -> {
                List<Map<String, Object>> parent = jdbc.sql("""
                                SELECT a.attname AS fk, k.confrelid::regclass::text AS parent, pa.attname AS key
                                FROM pg_catalog.pg_trigger t
                                    JOIN pg_catalog.pg_attribute a ON a.attrelid = t.tgrelid AND a.attnum = t.tgattr[0]
                                    JOIN pg_catalog.pg_constraint k ON k.conrelid = t.tgrelid AND k.contype = 'f'
                                        AND k.conkey = ARRAY[a.attnum]
                                    JOIN pg_catalog.pg_attribute pa ON pa.attrelid = k.confrelid
                                        AND pa.attnum = k.confkey[1]
                                WHERE t.tgrelid = ? AND t.tgname = 'parent_fixed'""")
                        .param(oid).query().listOfRows();
                if (parent.size() != 1) {
                    return List.of(name + ": parent_scope without its parent_fixed trigger on a foreign key");
                }
                Map<String, Object> link = parent.getFirst();
                helper = "shared.rls_protect_child_table('pg_temp." + relname + "', '" + link.get("fk") + "', '"
                        + link.get("parent") + "', '" + link.get("key") + "')";
                guardedColumn = (String) link.get("fk");
            }
            default -> {
                return List.of(name + ": policy " + policy + " is not one of the V1008 helpers'");
            }
        }
        List<String> problems = new ArrayList<>();
        Map<String, Object> actual = policies.getFirst();
        Map<String, Object> reference = OwnerDatabase.shared().call(owner -> {
            owner.sql("CREATE TEMPORARY TABLE " + relname + " (LIKE " + name + ") ON COMMIT DROP").update();
            owner.sql("SELECT " + helper).query().listOfRows();
            Map<String, Object> created = policies(owner, owner.sql("SELECT 'pg_temp." + relname
                    + "'::regclass::oid").query(Long.class).single()).getFirst();
            owner.sql("DROP TABLE pg_temp." + relname).update();
            return created;
        });
        for (String part : List.of("polname", "cmd", "permissive", "roles", "qual", "with_check")) {
            if (!String.valueOf(actual.get(part)).equals(String.valueOf(reference.get(part)))) {
                problems.add(name + ": its policy's " + part + " is not the canonical text of " + helper);
            }
        }
        Long guards = jdbc.sql("""
                        SELECT count(*) FROM pg_catalog.pg_trigger t
                            JOIN pg_catalog.pg_attribute a ON a.attrelid = t.tgrelid AND a.attnum = t.tgattr[0]
                        WHERE t.tgrelid = ? AND t.tgenabled = 'O' AND a.attname = ?
                          AND t.tgfoid = 'shared.rls_reject_scope_change()'::regprocedure""")
                .param(oid).param(guardedColumn).query(Long.class).single();
        if (guards != 1) {
            problems.add(name + ": no enabled RS002 trigger on " + guardedColumn);
        }
        return problems;
    }

    private static List<Map<String, Object>> policies(JdbcClient jdbc, long oid) {
        return jdbc.sql("""
                        SELECT polname, polcmd::text AS cmd, polpermissive AS permissive, polroles::text AS roles,
                               pg_catalog.pg_get_expr(polqual, polrelid) AS qual,
                               pg_catalog.pg_get_expr(polwithcheck, polrelid) AS with_check
                        FROM pg_catalog.pg_policy WHERE polrelid = ? ORDER BY polname""")
                .param(oid).query().listOfRows();
    }

    private static void dropScratch(JdbcClient owner) {
        owner.sql("DELETE FROM shared.rls_unscoped_table WHERE table_name::text LIKE ?").param(SCRATCH + ".%")
                .update();
        owner.sql("DROP SCHEMA IF EXISTS " + SCRATCH + " CASCADE").update();
    }
}
