package com.xavier.backend.config.persistence;

import com.xavier.backend.audit.AuditLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D163: after Flyway, the backend refuses to start when the pool's role is a superuser, has {@code BYPASSRLS} or owns
 * an application object (row-level security would not bind it), or when a table with a {@code store_id},
 * {@code from_store_id} or {@code to_store_id} column has neither row-level security enabled, forced and a policy, nor
 * a registration in {@code shared.rls_unscoped_table}. The expected set is data written by the migrations: every table
 * in {@code shared.rls_protected_table} (filled by the V1008 helpers and {@code rls_register_protected}) must keep
 * ENABLE, FORCE and a policy, so a restore without policies or a schema that lost all of them is refused; and every
 * store table of a schema that appears in either registry must be protected or registered unscoped. A module schema in
 * neither registry (before its policy slice) is not checked yet.
 *
 * <p>D170: in {@code PERMISSIVE} mode it logs ERROR and writes one system audit row
 * {@value #PERMISSIVE_STARTED}; the alert is {@code config.alerts.StoreScopeAlertSource}'s.
 */
@Component
@DependsOn("flywayInitializer")
class StoreScopeStartupCheck implements InitializingBean {

    static final String PERMISSIVE_STARTED = "STORE_SCOPE_PERMISSIVE_STARTED";

    private static final Logger log = LoggerFactory.getLogger(StoreScopeStartupCheck.class);
    private static final String APPLICATION_SCHEMA = "n.nspname NOT IN ('pg_catalog', 'information_schema', "
            + "'pg_toast') AND n.nspname NOT LIKE 'pg\\_temp\\_%' AND n.nspname NOT LIKE 'pg\\_toast\\_temp\\_%'";

    private final DataSource dataSource;
    private final StoreScopeEnforcement enforcement;
    private final AuditLog audit;
    private final PlatformTransactionManager transactions;

    StoreScopeStartupCheck(DataSource dataSource, Environment environment, AuditLog audit,
                           PlatformTransactionManager transactions) {
        this.dataSource = dataSource;
        this.enforcement = StoreScopeEnforcement.of(environment);
        this.audit = audit;
        this.transactions = transactions;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems(JdbcClient.create(dataSource));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The backend refuses to start (row-level security, ADR 0039): "
                    + String.join("; ", problems));
        }
        if (enforcement == StoreScopeEnforcement.PERMISSIVE) {
            log.error("Store-scope enforcement is PERMISSIVE: row-level security does not filter any store until a "
                    + "restart with ENFORCE (D170). AccessPolicy still decides.");
            new TransactionTemplate(transactions).executeWithoutResult(status -> audit.recordAsSystem(
                    PERMISSIVE_STARTED, "Installation", "store-scope", null, null,
                    "restaurantpro.store-scope.enforcement=PERMISSIVE", Map.of()));
        }
    }

    /** What is wrong for the connection's current role; empty when it may serve as the pool. */
    static List<String> problems(JdbcClient jdbc) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> role = jdbc.sql("SELECT rolsuper, rolbypassrls FROM pg_catalog.pg_roles "
                + "WHERE rolname = current_user").query().singleRow();
        if (Boolean.TRUE.equals(role.get("rolsuper"))) {
            problems.add("the pool's role is a superuser (it bypasses row-level security)");
        }
        if (Boolean.TRUE.equals(role.get("rolbypassrls"))) {
            problems.add("the pool's role has BYPASSRLS");
        }
        long owned = jdbc.sql("""
                        WITH me AS (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = current_user)
                        SELECT (SELECT count(*) FROM pg_catalog.pg_namespace n, me WHERE n.nspowner = me.oid AND %1$s)
                             + (SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n
                                    ON n.oid = c.relnamespace, me WHERE c.relowner = me.oid AND %1$s)
                             + (SELECT count(*) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n
                                    ON n.oid = p.pronamespace, me WHERE p.proowner = me.oid AND %1$s)
                             + (SELECT count(*) FROM pg_catalog.pg_database d, me
                                WHERE d.datname = current_database() AND d.datdba = me.oid)""".formatted(
                        APPLICATION_SCHEMA))
                .query(Long.class).single();
        if (owned > 0) {
            problems.add("the pool's role owns " + owned + " application object(s) (an owner is not bound by "
                    + "row-level security unless forced, and may change it)");
        }
        List<Boolean> registriesReadable = jdbc.sql("""
                        SELECT has_schema_privilege(current_user, n.oid, 'USAGE')
                           AND has_table_privilege(current_user, c.oid, 'SELECT')
                        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'shared' AND c.relname IN ('rls_unscoped_table', 'rls_protected_table')""")
                .query(Boolean.class).list();
        if (registriesReadable.size() < 2) {
            problems.add("the row-level security migrations are missing (shared.rls_unscoped_table, "
                    + "shared.rls_protected_table)");
            return problems;
        }
        if (registriesReadable.contains(false)) {
            problems.add("the pool's role cannot read the row-level security registries (run the role provisioning)");
            return problems;
        }
        List<String> unprotected = jdbc.sql("""
                        WITH protected AS (
                            SELECT c.oid FROM pg_catalog.pg_class c
                            WHERE c.relrowsecurity AND c.relforcerowsecurity
                              AND EXISTS (SELECT 1 FROM pg_catalog.pg_policy p WHERE p.polrelid = c.oid)),
                        registered AS (
                            SELECT table_name::oid AS oid FROM shared.rls_protected_table
                            UNION SELECT table_name::oid FROM shared.rls_unscoped_table),
                        registered_schema AS (
                            SELECT DISTINCT c.relnamespace FROM pg_catalog.pg_class c
                            WHERE c.oid IN (SELECT oid FROM registered)),
                        store_table AS (
                            SELECT c.oid FROM pg_catalog.pg_class c
                                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                            WHERE c.relkind IN ('r', 'p') AND %s
                              AND c.relnamespace IN (SELECT relnamespace FROM registered_schema)
                              AND EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a
                                          WHERE a.attrelid = c.oid AND NOT a.attisdropped
                                            AND a.attname IN ('store_id', 'from_store_id', 'to_store_id')))
                        SELECT oid::regclass::text FROM (
                            SELECT c.oid FROM shared.rls_protected_table r
                                JOIN pg_catalog.pg_class c ON c.oid = r.table_name
                            UNION
                            SELECT oid FROM store_table
                            WHERE oid NOT IN (SELECT table_name::oid FROM shared.rls_unscoped_table)) expected
                        WHERE oid NOT IN (SELECT oid FROM protected)
                        ORDER BY 1""".formatted(APPLICATION_SCHEMA))
                .query(String.class).list();
        if (!unprotected.isEmpty()) {
            problems.add("tables without row-level security (enabled, forced, with a policy) although registered as "
                    + "protected, or store tables of a covered schema with neither protection nor an unscoped "
                    + "registration: " + unprotected);
        }
        return problems;
    }
}
