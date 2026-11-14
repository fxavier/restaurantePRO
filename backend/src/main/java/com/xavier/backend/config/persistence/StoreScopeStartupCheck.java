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
 * a registration in {@code shared.rls_unscoped_table} (a restore that lost its policies, a dropped policy). The table
 * check covers every schema where row-level security has been set up, i.e. that has at least one protected or
 * registered table; the policy slices bring the remaining module schemas in, and the coverage test asserts each.
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
        List<Boolean> registryReadable = jdbc.sql("""
                        SELECT has_schema_privilege(current_user, n.oid, 'USAGE')
                           AND has_table_privilege(current_user, c.oid, 'SELECT')
                        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'shared' AND c.relname = 'rls_unscoped_table'""")
                .query(Boolean.class).list();
        if (registryReadable.isEmpty()) {
            problems.add("the row-level security migrations are missing (shared.rls_unscoped_table)");
            return problems;
        }
        if (!registryReadable.getFirst()) {
            problems.add("the pool's role cannot read shared.rls_unscoped_table (run the role provisioning)");
            return problems;
        }
        List<String> uncovered = jdbc.sql("""
                        WITH store_table AS (
                            SELECT c.oid, c.relnamespace
                            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                            WHERE c.relkind IN ('r', 'p') AND %s
                              AND EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a
                                          WHERE a.attrelid = c.oid AND NOT a.attisdropped
                                            AND a.attname IN ('store_id', 'from_store_id', 'to_store_id'))),
                        covered AS (
                            SELECT c.oid, c.relnamespace FROM pg_catalog.pg_class c
                            WHERE c.relrowsecurity AND c.relforcerowsecurity
                              AND EXISTS (SELECT 1 FROM pg_catalog.pg_policy p WHERE p.polrelid = c.oid)
                            UNION
                            SELECT c.oid, c.relnamespace FROM shared.rls_unscoped_table u
                                JOIN pg_catalog.pg_class c ON c.oid = u.table_name)
                        SELECT s.oid::regclass::text FROM store_table s
                        WHERE s.oid NOT IN (SELECT oid FROM covered)
                          AND s.relnamespace IN (SELECT relnamespace FROM covered)
                        ORDER BY 1""".formatted(APPLICATION_SCHEMA))
                .query(String.class).list();
        if (!uncovered.isEmpty()) {
            problems.add("store tables without row-level security (enabled, forced, with a policy) or an unscoped "
                    + "registration: " + uncovered);
        }
        return problems;
    }
}
