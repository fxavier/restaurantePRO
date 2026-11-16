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

    /**
     * D168, D179 and the spec's SQL contracts: the only definer functions. Each pins {@code search_path}, is not
     * executable by {@code PUBLIC}, is executable by the application role, and has its own test (security rule 9):
     * {@code StoreProbeTenantTest} ({@code store_of}), the inventory suites ({@code stock_transfer_stores}),
     * {@code TenancyFunctionsTest} (the tenancy functions).
     */
    static final Set<String> SECURITY_DEFINER_ALLOWED = Set.of("shared.store_of(regclass,uuid)",
            "inventory.stock_transfer_stores(uuid)",
            "tenancy.resolve_membership(text,text,uuid,text[])",
            "tenancy.active_tenant_ids()",
            "tenancy.grant_membership(uuid,text,text,text,uuid,timestamp with time zone,timestamp with time zone)",
            "tenancy.revoke_membership(uuid)",
            "tenancy.tenant_status(uuid)",
            "tenancy.activate_tenant(uuid)");

    /**
     * R6: the modules whose every table is TENANT* or PLATFORM ({@link #assertModuleTenantProtected}). A module joins
     * when its slice converts it (S74a-d, S72 for identity); S76 makes the assertion strict over every schema.
     */
    static final List<String> TENANT_PROTECTED_MODULES = List.of("organization", "tenancy");

    /** R6 tables converted ahead of their module (the shared kernel keeps Spring Session until S72). */
    static final List<String> TENANT_PROTECTED_TABLES = List.of("shared.idempotency_record");

    private static final String SCRATCH = "rls_scratch_coverage";

    @Test
    void theSchemasOfThePrefactorAreClassified() {
        for (String schema : List.of("shared", "public", "organization", "identity", "customers", "tenancy")) {
            assertModuleClassified(schema);
        }
    }

    /** D179 (A88, per module until S76): every table of a converted module is TENANT* or PLATFORM with a reason. */
    @Test
    void theConvertedModulesAreTenantProtected() {
        TENANT_PROTECTED_MODULES.forEach(RowLevelSecurityCoverageTest::assertModuleTenantProtected);
        TENANT_PROTECTED_TABLES.forEach(table -> assertThat(tenantProblems(table)).as(table).isEmpty());
    }

    /** D177: the application role holds no privilege on any tenancy table; it reaches them through functions. */
    @Test
    void theApplicationRoleHasNoPrivilegeOnTenancyTables() {
        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT c.oid::regclass::text FROM pg_catalog.pg_class c
                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'tenancy' AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
                          AND (has_table_privilege(:role, c.oid, 'SELECT')
                               OR has_table_privilege(:role, c.oid, 'INSERT')
                               OR has_table_privilege(:role, c.oid, 'UPDATE')
                               OR has_table_privilege(:role, c.oid, 'DELETE'))""")
                .param("role", TestcontainersConfiguration.APP_ROLE).query(String.class).list()).isEmpty();
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM pg_catalog.pg_class c "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'tenancy' "
                        + "AND c.relkind = 'r'").query(Long.class).single()).as("tenancy has tables").isPositive();
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
        assertThat(registryProblems(schemas)).as("each table in exactly one registry").isEmpty();
    }

    /**
     * A75 as R6 converts modules (it holds whatever slice merges first): the three registries (PROTECTED, UNSCOPED,
     * PLATFORM) partition every table of {@code schemas}, each table in exactly one, and UNSCOPED is left only in a
     * module not yet converted ({@link #TENANT_PROTECTED_MODULES}).
     */
    static List<String> registryProblems(List<String> schemas) {
        List<Map<String, Object>> tables = OwnerDatabase.shared().jdbc().sql("""
                        SELECT c.oid::regclass::text AS name, n.nspname AS schema,
                               (EXISTS (SELECT 1 FROM shared.rls_protected_table r WHERE r.table_name = c.oid))::int
                             + (EXISTS (SELECT 1 FROM shared.rls_unscoped_table r WHERE r.table_name = c.oid))::int
                             + (EXISTS (SELECT 1 FROM shared.rls_platform_table r WHERE r.table_name = c.oid))::int
                                   AS registries,
                               EXISTS (SELECT 1 FROM shared.rls_unscoped_table r WHERE r.table_name = c.oid)
                                   AS unscoped
                        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE c.relkind IN ('r', 'p') AND n.nspname = ANY (CAST(? AS text[]))
                        ORDER BY 1""").param("{" + String.join(",", schemas) + "}").query().listOfRows();
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            long registries = ((Number) table.get("registries")).longValue();
            if (registries != 1) {
                problems.add(table.get("name") + ": in " + registries + " registries, not exactly one");
            }
            if (Boolean.TRUE.equals(table.get("unscoped"))
                    && TENANT_PROTECTED_MODULES.contains((String) table.get("schema"))) {
                problems.add(table.get("name") + ": UNSCOPED in a module converted to tenant tables");
            }
        }
        return problems;
    }

    /** The partition is red-capable: an unregistered table, a table in two registries, UNSCOPED in a converted one. */
    @Test
    void theRegistryPartitionCatchesAMissingADoubleAndAStaleUnscopedTable() {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        dropScratch(owner);
        try {
            OwnerDatabase.shared().tx(jdbc -> {
                jdbc.sql("CREATE SCHEMA " + SCRATCH).update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".stray (id uuid PRIMARY KEY)").update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".twice (id uuid PRIMARY KEY)").update();
                jdbc.sql("SELECT shared.rls_register_unscoped('" + SCRATCH + ".twice', 'x'), "
                        + "shared.rls_register_protected('" + SCRATCH + ".twice')").query().listOfRows();
            });
            assertThat(registryProblems(List.of(SCRATCH))).containsExactly(
                    SCRATCH + ".stray: in 0 registries, not exactly one",
                    SCRATCH + ".twice: in 2 registries, not exactly one");
            owner.sql("INSERT INTO shared.rls_unscoped_table VALUES ('organization.store', 'stale')").update();
            assertThat(registryProblems(List.of("organization")))
                    .contains("organization.store: in 2 registries, not exactly one",
                            "organization.store: UNSCOPED in a module converted to tenant tables");
        } finally {
            owner.sql("DELETE FROM shared.rls_unscoped_table WHERE table_name = 'organization.store'::regclass")
                    .update();
            dropScratch(owner);
        }
    }

    @Test
    void noSecurityDefinerFunctionOutsideTheAllowList() {
        List<Map<String, Object>> definers = OwnerDatabase.shared().jdbc().sql("""
                        SELECT p.oid::regprocedure::text AS name,
                               coalesce(array_to_string(p.proconfig, ','), '') AS config,
                               p.proacl IS NULL OR EXISTS (SELECT 1 FROM aclexplode(p.proacl) a
                                                           WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE')
                                   AS public_execute,
                               has_function_privilege(:role, p.oid, 'EXECUTE') AS app_execute
                        FROM pg_catalog.pg_proc p
                            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                        WHERE p.prosecdef AND n.nspname NOT IN ('pg_catalog', 'information_schema')""")
                .param("role", TestcontainersConfiguration.APP_ROLE).query().listOfRows();

        assertThat(definers).extracting(row -> (String) row.get("name")).isSubsetOf(SECURITY_DEFINER_ALLOWED)
                .contains("shared.store_of(regclass,uuid)", "tenancy.resolve_membership(text,text,uuid,text[])",
                        "tenancy.active_tenant_ids()");
        assertThat(definers).filteredOn(row -> !"inventory.stock_transfer_stores(uuid)".equals(row.get("name")))
                .allSatisfy(row -> {
                    assertThat((String) row.get("config")).as("%s pins search_path", row.get("name"))
                            .isEqualTo("search_path=pg_catalog");
                    assertThat(row.get("public_execute")).as("%s is not PUBLIC's", row.get("name"))
                            .isEqualTo(false);
                    assertThat(row.get("app_execute")).as("%s is the application role's", row.get("name"))
                            .isEqualTo(true);
                });
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
     * the same V1008/V1009 helper applied to a copy) and its {@code RS002} trigger. A CHILD table's parent is read from
     * the foreign key of its {@code parent_fixed} column: single-column, or {@code (tenant_id, x)} once converted. {@code specialTables} (D167/D168:
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
                                   OR EXISTS (SELECT 1 FROM shared.rls_platform_table f WHERE f.table_name = c.oid)
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
                problems.add(name + ": neither registered UNSCOPED or PLATFORM nor protected (ENABLE + FORCE)");
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
            Set.of("tenant_rows"), "shared.rls_protect_tenant_table",
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
        // D179: the restrictive tenant_scope of a converted table comes with its store set (the helper adds it first)
        Set<String> storeNames = new java.util.TreeSet<>(names);
        boolean tenant = storeNames.remove("tenant_scope");
        String function = HELPER_OF_POLICIES.get(storeNames);
        if (function == null || (function.equals("shared.rls_protect_tenant_table") && !tenant)) {
            return List.of(name + ": policies " + names + " are not the set of one V1008/V1009/V1010 helper");
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
                                    AND a.attnum = ANY (k.conkey)
                                    AND (cardinality(k.conkey) = 1 OR (cardinality(k.conkey) = 2
                                        AND EXISTS (SELECT 1 FROM pg_catalog.pg_attribute ta
                                                    WHERE ta.attrelid = k.conrelid AND ta.attname = 'tenant_id'
                                                      AND ta.attnum = ANY (k.conkey))))
                                JOIN pg_catalog.pg_attribute pa ON pa.attrelid = k.confrelid
                                    AND pa.attnum = k.confkey[array_position(k.conkey, a.attnum)]
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
            owner.sql("CREATE TEMPORARY TABLE " + relname + " (LIKE " + name + " INCLUDING DEFAULTS INCLUDING INDEXES) "
                    + "ON COMMIT DROP").update();
            owner.sql("SELECT " + helper).query().listOfRows();
            List<Map<String, Object>> created = policies(owner, owner.sql("SELECT 'pg_temp." + relname
                    + "'::regclass::oid").query(Long.class).single());
            owner.sql("DELETE FROM shared.rls_protected_table WHERE table_name = 'pg_temp." + relname
                    + "'::regclass").update();
            owner.sql("DELETE FROM shared.rls_tenant_table WHERE table_name = 'pg_temp." + relname
                    + "'::regclass").update();
            owner.sql("DROP TABLE pg_temp." + relname).update();
            return created;
        });
        if (reference.size() != policies.size()) {
            problems.add(name + ": policies " + names + " differ from the canonical set of " + helper);
            return problems;
        }
        for (int i = 0; i < policies.size(); i++) {
            Map<String, Object> actual = policies.get(i);
            for (String part : List.of("polname", "cmd", "permissive", "roles", "qual", "with_check")) {
                if (!String.valueOf(actual.get(part)).equals(String.valueOf(reference.get(i).get(part)))) {
                    problems.add(name + ": its policy " + actual.get("polname") + "'s " + part
                            + " is not the canonical text of " + helper);
                }
            }
        }
        if (tenant) {
            problems.addAll(tenantProblems(name));
        }
        if (function.equals("shared.rls_protect_tenant_table")) {
            return problems; // TENANT class: no store column to guard
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

    /**
     * D179 (security rule 2), per module: every table of {@code schema} is PLATFORM (registered with a reason) or a
     * TENANT* table: {@code tenant_id uuid NOT NULL DEFAULT shared.rls_tenant_id()}, ENABLE + FORCE, the canonical
     * restrictive {@code tenant_scope}, {@code UNIQUE (tenant_id, id)} (or a primary key that leads with
     * {@code tenant_id} when there is no id), the enabled {@code tenant_fixed} RS002 trigger, and its registration in
     * {@code shared.rls_tenant_table}. A module calls it from its isolation test once its slice converted it.
     */
    public static void assertModuleTenantProtected(String schema) {
        List<String> tables = OwnerDatabase.shared().jdbc().sql("""
                        SELECT c.oid::regclass::text FROM pg_catalog.pg_class c
                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
                          AND NOT EXISTS (SELECT 1 FROM shared.rls_platform_table f WHERE f.table_name = c.oid)
                        ORDER BY 1""").param(schema).query(String.class).list();
        long all = OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM pg_catalog.pg_class c JOIN "
                + "pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relkind IN ('r', 'p')")
                .param(schema).query(Long.class).single();
        assertThat(all).as("schema %s has tables", schema).isPositive();
        List<String> problems = new ArrayList<>();
        tables.forEach(table -> problems.addAll(tenantProblems(table)));
        assertThat(problems).as("tenant protection of schema %s", schema).isEmpty();
    }

    /** What keeps {@code table} from being a canonical TENANT* table (empty when it is one). */
    static List<String> tenantProblems(String table) {
        JdbcClient jdbc = OwnerDatabase.shared().jdbc();
        Map<String, Object> facts = jdbc.sql("""
                        SELECT c.relrowsecurity AND c.relforcerowsecurity AS forced,
                               EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a
                                           JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                                       WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND a.attnotnull
                                         AND a.atttypid = 'uuid'::regtype
                                         AND EXISTS (SELECT 1 FROM pg_catalog.pg_depend dep
                                                     WHERE dep.classid = 'pg_catalog.pg_attrdef'::regclass
                                                       AND dep.objid = d.oid
                                                       AND dep.refobjid = 'shared.rls_tenant_id()'::regprocedure))
                                   AS tenant_column,
                               (SELECT count(*) FROM pg_catalog.pg_policy p WHERE p.polrelid = c.oid
                                  AND p.polname = 'tenant_scope' AND NOT p.polpermissive AND p.polcmd = '*'
                                  AND pg_catalog.pg_get_expr(p.polqual, p.polrelid) = :canonical
                                  AND pg_catalog.pg_get_expr(p.polwithcheck, p.polrelid) = :canonical) AS policy,
                               EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a WHERE a.attrelid = c.oid
                                       AND a.attname = 'id' AND NOT a.attisdropped) AS has_id,
                               EXISTS (SELECT 1 FROM pg_catalog.pg_index i
                                       WHERE i.indrelid = c.oid AND i.indisunique AND i.indpred IS NULL
                                         AND i.indnkeyatts = 2
                                         AND pg_catalog.pg_get_indexdef(i.indexrelid, 1, true) = 'tenant_id'
                                         AND pg_catalog.pg_get_indexdef(i.indexrelid, 2, true) = 'id') AS tenant_id_key,
                               EXISTS (SELECT 1 FROM pg_catalog.pg_index i
                                       WHERE i.indrelid = c.oid AND i.indisprimary
                                         AND pg_catalog.pg_get_indexdef(i.indexrelid, 1, true) = 'tenant_id')
                                   AS tenant_primary_key,
                               EXISTS (SELECT 1 FROM pg_catalog.pg_trigger t
                                           JOIN pg_catalog.pg_attribute a ON a.attrelid = t.tgrelid
                                               AND a.attnum = t.tgattr[0]
                                       WHERE t.tgrelid = c.oid AND t.tgenabled = 'O' AND a.attname = 'tenant_id'
                                         AND t.tgfoid = 'shared.rls_reject_tenant_change()'::regprocedure) AS fixed,
                               EXISTS (SELECT 1 FROM shared.rls_tenant_table r WHERE r.table_name = c.oid)
                                   AS registered
                        FROM pg_catalog.pg_class c WHERE c.oid = CAST(:table AS regclass)""")
                .param("table", table).param("canonical", CANONICAL_TENANT_PREDICATE).query().singleRow();
        List<String> problems = new ArrayList<>();
        if (!Boolean.TRUE.equals(facts.get("tenant_column"))) {
            problems.add(table + ": no tenant_id uuid NOT NULL DEFAULT shared.rls_tenant_id()");
        }
        if (!Boolean.TRUE.equals(facts.get("forced"))) {
            problems.add(table + ": row-level security not enabled and forced");
        }
        if (((Number) facts.get("policy")).longValue() != 1) {
            problems.add(table + ": no canonical restrictive tenant_scope policy");
        }
        boolean key = Boolean.TRUE.equals(facts.get("has_id")) ? Boolean.TRUE.equals(facts.get("tenant_id_key"))
                : Boolean.TRUE.equals(facts.get("tenant_primary_key"));
        if (!key) {
            problems.add(table + ": no UNIQUE (tenant_id, id) (or primary key leading with tenant_id)");
        }
        if (!Boolean.TRUE.equals(facts.get("fixed"))) {
            problems.add(table + ": no enabled tenant_fixed RS002 trigger on tenant_id");
        }
        if (!Boolean.TRUE.equals(facts.get("registered"))) {
            problems.add(table + ": not in shared.rls_tenant_table");
        }
        return problems;
    }

    /**
     * The recipe makes an in-module foreign key composite, {@code (tenant_id, parent_id) REFERENCES parent (tenant_id,
     * id)}: a converted CHILD table is still classified and canonical, its parent found through that key, as a
     * single-column one still is for a module not yet converted.
     */
    @Test
    void aConvertedChildTableIsFoundThroughItsCompositeForeignKey() {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        dropScratch(owner);
        try {
            OwnerDatabase.shared().tx(jdbc -> {
                jdbc.sql("CREATE SCHEMA " + SCRATCH).update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".parent (id uuid PRIMARY KEY, store_id uuid NOT NULL, "
                        + "tenant_id uuid NOT NULL DEFAULT shared.rls_tenant_id(), UNIQUE (tenant_id, id))").update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".child (id uuid PRIMARY KEY, parent_id uuid NOT NULL, "
                        + "tenant_id uuid NOT NULL DEFAULT shared.rls_tenant_id(), UNIQUE (tenant_id, id), "
                        + "FOREIGN KEY (tenant_id, parent_id) REFERENCES " + SCRATCH + ".parent (tenant_id, id))")
                        .update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".legacy_child (id uuid PRIMARY KEY, parent_id uuid NOT NULL "
                        + "REFERENCES " + SCRATCH + ".parent (id))").update();
                jdbc.sql("SELECT shared.rls_protect_store_table('" + SCRATCH + ".parent'), "
                        + "shared.rls_protect_child_table('" + SCRATCH + ".child', 'parent_id', '" + SCRATCH
                        + ".parent', 'id'), shared.rls_protect_child_table('" + SCRATCH + ".legacy_child', "
                        + "'parent_id', '" + SCRATCH + ".parent', 'id')").query().listOfRows();
            });

            assertModuleClassified(SCRATCH);
            assertTableTenantProtected(SCRATCH + ".child");
            assertThat(com.xavier.backend.AppRoleSession.classification(SCRATCH))
                    .containsEntry("child", "CHILD:" + SCRATCH + ".parent")
                    .containsEntry("legacy_child", "CHILD:" + SCRATCH + ".parent");
            CompositeForeignKeyCoverageTest.assertTableKeysIncludeTheTenant(SCRATCH + ".child");
        } finally {
            dropScratch(owner);
        }
    }

    /** {@link #tenantProblems} as an assertion, for a module slice that converts one table at a time. */
    public static void assertTableTenantProtected(String table) {
        assertThat(tenantProblems(table)).as("tenant protection of %s", table).isEmpty();
    }

    /** The deparsed tenant predicate of {@code shared.rls_protect_tenant}, as pg_get_expr prints it. */
    static final String CANONICAL_TENANT_PREDICATE = "(tenant_id = ( SELECT shared.rls_tenant_id() AS rls_tenant_id))";

    /** The tenant helper is red-capable: a table without its column, policy, key or trigger is reported. */
    @Test
    void theTenantHelperCatchesAMissingColumnPolicyKeyAndTrigger() {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        dropScratch(owner);
        try {
            OwnerDatabase.shared().tx(jdbc -> {
                jdbc.sql("CREATE SCHEMA " + SCRATCH).update();
                jdbc.sql("CREATE TABLE " + SCRATCH + ".company (id uuid PRIMARY KEY, "
                        + "tenant_id uuid NOT NULL DEFAULT shared.rls_tenant_id(), UNIQUE (tenant_id, id))").update();
                jdbc.sql("SELECT shared.rls_protect_tenant_table('" + SCRATCH + ".company')").query().listOfRows();
            });
            assertModuleTenantProtected(SCRATCH);
            assertModuleClassified(SCRATCH);

            owner.sql("CREATE TABLE " + SCRATCH + ".stray (id uuid PRIMARY KEY)").update();
            assertThatThrownBy(() -> assertModuleTenantProtected(SCRATCH)).hasMessageContaining(SCRATCH + ".stray")
                    .hasMessageContaining("no tenant_id");
            owner.sql("DROP TABLE " + SCRATCH + ".stray").update();

            owner.sql("ALTER POLICY tenant_scope ON " + SCRATCH + ".company USING (true)").update();
            assertThatThrownBy(() -> assertModuleTenantProtected(SCRATCH))
                    .hasMessageContaining("no canonical restrictive tenant_scope");
            owner.sql("ALTER POLICY tenant_scope ON " + SCRATCH + ".company USING (" + CANONICAL_TENANT_PREDICATE
                    + ")").update();
            assertModuleTenantProtected(SCRATCH);

            owner.sql("ALTER TABLE " + SCRATCH + ".company DISABLE TRIGGER tenant_fixed").update();
            assertThatThrownBy(() -> assertModuleTenantProtected(SCRATCH)).hasMessageContaining("tenant_fixed");
            owner.sql("ALTER TABLE " + SCRATCH + ".company ENABLE TRIGGER tenant_fixed").update();

            owner.sql("ALTER TABLE " + SCRATCH + ".company DROP CONSTRAINT company_tenant_id_id_key").update();
            assertThatThrownBy(() -> assertModuleTenantProtected(SCRATCH))
                    .hasMessageContaining("UNIQUE (tenant_id, id)");
        } finally {
            dropScratch(owner);
        }
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
        owner.sql("DELETE FROM shared.rls_tenant_table WHERE table_name::text LIKE ?").param(SCRATCH + ".%")
                .update();
        owner.sql("DROP SCHEMA IF EXISTS " + SCRATCH + " CASCADE").update();
    }
}
