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
 * Each policy slice asserts its own schemas with {@link #assertModuleClassified}; S70 adds the all-schemas assertion
 * (A75) and the per-command STORE_OR_ALL policies of V1009.
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

    /**
     * D167 SPECIAL tables, accepted with hand-written policies: the audit trail (inserts open), the inter-store
     * transfer and its lines (either end) and the warehouse (open reads).
     */
    static final Map<String, String[]> SPECIAL_TABLES = Map.of(
            "audit", new String[] {"audit_event"},
            "inventory", new String[] {"warehouse", "stock_transfer", "stock_transfer_line"});

    /**
     * A75 strict over every schema: the schemas that hold a table are exactly the module folders of the migrations
     * (MigrationNumberingTest) plus {@code public}, and every table of each is classified.
     */
    @Test
    void everySchemaOfTheInstallationIsClassified() {
        Set<String> expected = new java.util.TreeSet<>(MigrationNumberingTest.BLOCKS.keySet());
        expected.add("public");
        List<String> schemas = OwnerDatabase.shared().jdbc().sql("""
                        SELECT DISTINCT n.nspname FROM pg_catalog.pg_class c
                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                          AND n.nspname NOT LIKE 'pg\\_%'
                        ORDER BY 1""").query(String.class).list();

        assertThat(schemas).as("schemas holding tables").containsExactlyInAnyOrderElementsOf(expected);
        for (String schema : schemas) {
            assertModuleClassified(schema, SPECIAL_TABLES.getOrDefault(schema, new String[0]));
        }
        Map<String, Object> counts = OwnerDatabase.shared().jdbc().sql("""
                        SELECT (SELECT count(*) FROM shared.rls_protected_table) AS protected,
                               (SELECT count(*) FROM shared.rls_unscoped_table) AS unscoped""").query().singleRow();
        assertThat(counts).as("A75: 97 protected after R5; 58 + 4 (R5) unscoped, the Flyway history and the two registries")
                .containsEntry("protected", 97L).containsEntry("unscoped", 65L);
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

    /** The Flyway history is read-only to the pool, whatever the owner's default privileges granted it. */
    @Test
    void theApplicationRoleCannotWriteTheMigrationHistory() throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                TestcontainersConfiguration.postgres().getJdbcUrl(), TestcontainersConfiguration.APP_ROLE,
                TestcontainersConfiguration.appPassword())) {
            JdbcClient app = JdbcClient.create(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    connection, true));

            assertThat(app.sql("SELECT count(*) FROM public.flyway_schema_history").query(Long.class).single())
                    .as("readable").isPositive();
            for (String write : List.of(
                    "INSERT INTO public.flyway_schema_history (installed_rank, version, description, type, script, "
                            + "installed_by, execution_time, success) VALUES (999999, '999999', 'x', 'SQL', 'x', "
                            + "'x', 0, true)",
                    "UPDATE public.flyway_schema_history SET success = success",
                    "DELETE FROM public.flyway_schema_history WHERE version = '1008'")) {
                assertThatThrownBy(() -> app.sql(write).update()).as(write)
                        .rootCause().hasMessageContaining("permission denied for table flyway_schema_history");
            }
        }
    }

    /** Both registries are written by migrations only: the pool may read them, never add or remove a row. */
    @Test
    void theApplicationRoleCannotChangeTheRegistries() throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                TestcontainersConfiguration.postgres().getJdbcUrl(), TestcontainersConfiguration.APP_ROLE,
                TestcontainersConfiguration.appPassword())) {
            JdbcClient app = JdbcClient.create(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    connection, true));
            for (String registry : List.of("shared.rls_protected_table", "shared.rls_unscoped_table")) {
                // readable (rls_protected_table is still empty before the policy slices)
                long before = app.sql("SELECT count(*) FROM " + registry).query(Long.class).single();
                String insert = registry.endsWith("unscoped_table")
                        ? "INSERT INTO " + registry + " VALUES ('organization.store', 'hidden by a bug')"
                        : "INSERT INTO " + registry + " VALUES ('organization.store')";

                assertThatThrownBy(() -> app.sql(insert).update()).as(insert).rootCause()
                        .hasMessageContaining("violates row-level security policy");
                assertThat(app.sql("DELETE FROM " + registry).update()).as("DELETE FROM %s", registry).isZero();
                assertThat(app.sql("SELECT count(*) FROM " + registry).query(Long.class).single()).isEqualTo(before);
            }
        }
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
                jdbc.sql("CREATE TABLE " + SCRATCH + ".entry (id uuid PRIMARY KEY, page_id uuid NOT NULL "
                        + "REFERENCES " + SCRATCH + ".page (id))").update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".company (id uuid PRIMARY KEY)").update();
                jdbc.sql("SELECT shared.rls_protect_store_table('" + SCRATCH + ".parent'), "
                        + "shared.rls_protect_store_or_all_table('" + SCRATCH + ".page'), "
                        + "shared.rls_protect_child_table('" + SCRATCH + ".child', 'parent_id', '" + SCRATCH
                        + ".parent', 'id'), shared.rls_protect_store_or_all_child_table('" + SCRATCH
                        + ".entry', 'page_id', '" + SCRATCH + ".page', 'id'), shared.rls_register_unscoped('"
                        + SCRATCH + ".company', 'company-wide')")
                        .query().listOfRows();
            });
            assertModuleClassified(SCRATCH);

            owner.sql("CREATE TABLE " + SCRATCH + ".stray (id uuid PRIMARY KEY, store_id uuid)").update();
            assertThatThrownBy(() -> assertModuleClassified(SCRATCH)).hasMessageContaining(SCRATCH + ".stray");
            owner.sql("DROP TABLE " + SCRATCH + ".stray").update();

            owner.sql("ALTER TABLE " + SCRATCH + ".child NO FORCE ROW LEVEL SECURITY").update();
            assertThatThrownBy(() -> assertModuleClassified(SCRATCH)).hasMessageContaining(SCRATCH + ".child");
            owner.sql("ALTER TABLE " + SCRATCH + ".child FORCE ROW LEVEL SECURITY").update();

            // the V1008 gap: a DELETE policy that borrows the read rule (store_id IS NULL OR VIS)
            owner.sql("DROP POLICY store_or_all_delete ON " + SCRATCH + ".page").update();
            owner.sql("CREATE POLICY store_or_all_delete ON " + SCRATCH + ".page FOR DELETE USING (store_id IS NULL "
                    + "OR " + visible("store_id") + ")").update();
            assertThatThrownBy(() -> assertModuleClassified(SCRATCH)).hasMessageContaining(SCRATCH + ".page");
            owner.sql("DROP POLICY store_or_all_delete ON " + SCRATCH + ".page").update();
            assertThatThrownBy(() -> assertModuleClassified(SCRATCH)).as("a policy missing from the set")
                    .hasMessageContaining(SCRATCH + ".page");
            owner.sql("CREATE POLICY store_or_all_delete ON " + SCRATCH + ".page FOR DELETE USING ("
                    + visible("store_id") + ")").update();
            assertModuleClassified(SCRATCH);

            owner.sql("ALTER POLICY store_or_all_parent_delete ON " + SCRATCH + ".entry USING (true)").update();
            assertThatThrownBy(() -> assertModuleClassified(SCRATCH)).hasMessageContaining(SCRATCH + ".entry");
        } finally {
            dropScratch(owner);
        }
    }

    /**
     * D168 amended (V1009): a STORE_OR_ALL table, and the child of one, reads every-store rows in any scope but writes
     * and deletes only in VIS: one policy per command; only the read and the UPDATE's USING (so a store scope's update
     * of an every-store row raises 42501) carry the read rule, never a WITH CHECK nor the DELETE.
     */
    @Test
    void theStoreOrAllHelpersReadEveryStoreRowsButWriteOnlyInScope() {
        String read = "((store_id IS NULL) OR " + visible("store_id") + ")";
        String write = visible("store_id");
        assertThat(policyTexts("catalog.quick_page")).containsExactlyInAnyOrder(
                "store_or_all_delete d " + write + " null",
                "store_or_all_insert a null " + write,
                "store_or_all_read r " + read + " null",
                "store_or_all_update w " + read + " " + write);

        List<String> entry = policyTexts("catalog.quick_page_entry");
        assertThat(entry).extracting(text -> text.split(" ")[0] + " " + text.split(" ")[1]).containsExactlyInAnyOrder(
                "store_or_all_parent_delete d", "store_or_all_parent_insert a", "store_or_all_parent_read r",
                "store_or_all_parent_update w");
        assertThat(entry).filteredOn(text -> text.startsWith("store_or_all_parent_read")
                        || text.startsWith("store_or_all_parent_update"))
                .hasSize(2).allSatisfy(text -> assertThat(text).contains("p.store_id IS NULL"));
        // the update's WITH CHECK (its last part) and the insert and delete carry VIS only
        assertThat(entry).filteredOn(text -> text.startsWith("store_or_all_parent_update"))
                .singleElement().satisfies(text -> assertThat(text.substring(text.lastIndexOf("EXISTS")))
                        .doesNotContain("IS NULL"));
        assertThat(entry).filteredOn(text -> text.startsWith("store_or_all_parent_insert")
                        || text.startsWith("store_or_all_parent_delete"))
                .hasSize(2).allSatisfy(text -> assertThat(text).doesNotContain("IS NULL").contains("p.store_id"));
    }

    /** The deparsed VIS(column) of D168, as pg_get_expr prints it. */
    private static String visible(String column) {
        return "(( SELECT shared.rls_all_stores() AS rls_all_stores) OR (" + column
                + " = ANY (( SELECT shared.rls_store_ids() AS rls_store_ids)::uuid[])))";
    }

    private static List<String> policyTexts(String table) {
        return policies(OwnerDatabase.shared().jdbc(), OwnerDatabase.shared().jdbc()
                .sql("SELECT ?::regclass::oid").param(table).query(Long.class).single()).stream()
                .map(policy -> policy.get("polname") + " " + policy.get("cmd") + " " + policy.get("qual") + " "
                        + policy.get("with_check")).toList();
    }

    /**
     * Every table of {@code schema} is classified (D167): registered in {@code shared.rls_unscoped_table}, or protected
     * with ENABLE + FORCE, the canonical policy set of one helper ({@link #HELPER_OF_POLICIES}: {@code store_scope},
     * the four {@code store_or_all_*}, {@code parent_scope} or the four {@code store_or_all_parent_*}, compared with
     * the same V1008/V1009 helper applied to a copy) and its {@code RS002} trigger. {@code specialTables} (D167/D168:
     * {@code audit_event}, {@code stock_transfer}, {@code stock_transfer_line}, {@code warehouse}, see
     * {@link #SPECIAL_TABLES}) need only ENABLE, FORCE and a policy; their own slice proves their policies.
     */
    public static void assertModuleClassified(String schema, String... specialTables) {
        Set<String> special = Set.of(specialTables);
        List<String> problems = new ArrayList<>();
        JdbcClient jdbc = OwnerDatabase.shared().jdbc();
        List<Map<String, Object>> tables = jdbc.sql("""
                        SELECT c.oid, c.oid::regclass::text AS name, c.relname, c.relrowsecurity AS enabled,
                               c.relforcerowsecurity AS forced,
                               EXISTS (SELECT 1 FROM shared.rls_unscoped_table u WHERE u.table_name = c.oid)
                                   AS registered,
                               EXISTS (SELECT 1 FROM shared.rls_protected_table p WHERE p.table_name = c.oid)
                                   AS protected_registered
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
            if (!Boolean.TRUE.equals(table.get("protected_registered"))) {
                problems.add(name + ": protected but not in shared.rls_protected_table (use the V1008 helpers, or "
                        + "shared.rls_register_protected for a special table)");
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

    /** The policy names each helper writes (V1008, V1009); a protected table carries exactly one of these sets. */
    static final Map<Set<String>, String> HELPER_OF_POLICIES = Map.of(
            Set.of("store_scope"), "shared.rls_protect_store_table",
            Set.of("store_or_all_read", "store_or_all_insert", "store_or_all_update", "store_or_all_delete"),
            "shared.rls_protect_store_or_all_table",
            Set.of("parent_scope"), "shared.rls_protect_child_table",
            Set.of("store_or_all_parent_read", "store_or_all_parent_insert", "store_or_all_parent_update",
                    "store_or_all_parent_delete"), "shared.rls_protect_store_or_all_child_table");

    private static List<String> canonicalProblems(String name, String relname, long oid,
                                                  List<Map<String, Object>> policies) {
        Set<String> names = new java.util.TreeSet<>();
        policies.forEach(policy -> names.add((String) policy.get("polname")));
        String function = HELPER_OF_POLICIES.get(names);
        if (function == null) {
            return List.of(name + ": policies " + names + " are not the set of one V1008/V1009 helper");
        }
        JdbcClient jdbc = OwnerDatabase.shared().jdbc();
        String helper;
        String guardedColumn;
        if (function.endsWith("child_table")) {
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
                return List.of(name + ": " + names + " without its parent_fixed trigger on a foreign key");
            }
            Map<String, Object> link = parent.getFirst();
            helper = function + "('pg_temp." + relname + "', '" + link.get("fk") + "', '" + link.get("parent")
                    + "', '" + link.get("key") + "')";
            guardedColumn = (String) link.get("fk");
        } else {
            helper = function + "('pg_temp." + relname + "')";
            guardedColumn = "store_id";
        }
        List<String> problems = new ArrayList<>();
        List<Map<String, Object>> reference = OwnerDatabase.shared().call(owner -> {
            owner.sql("CREATE TEMPORARY TABLE " + relname + " (LIKE " + name + ") ON COMMIT DROP").update();
            owner.sql("SELECT " + helper).query().listOfRows();
            List<Map<String, Object>> created = policies(owner, owner.sql("SELECT 'pg_temp." + relname
                    + "'::regclass::oid").query(Long.class).single());
            owner.sql("DELETE FROM shared.rls_protected_table WHERE table_name = 'pg_temp." + relname
                    + "'::regclass").update();
            owner.sql("DROP TABLE pg_temp." + relname).update();
            return created;
        });
        for (int i = 0; i < policies.size(); i++) {
            Map<String, Object> actual = policies.get(i);
            for (String part : List.of("polname", "cmd", "permissive", "roles", "qual", "with_check")) {
                if (!String.valueOf(actual.get(part)).equals(String.valueOf(reference.get(i).get(part)))) {
                    problems.add(name + ": its policy " + actual.get("polname") + "'s " + part
                            + " is not the canonical text of " + helper);
                }
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
        owner.sql("DELETE FROM shared.rls_protected_table WHERE table_name::text LIKE ?").param(SCRATCH + ".%")
                .update();
        owner.sql("DROP SCHEMA IF EXISTS " + SCRATCH + " CASCADE").update();
    }
}
