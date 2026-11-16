package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D180 (indexes): in the tenant tables of catalog, promotions, reporting and audit, every composite index and every
 * index on {@code store_id} leads with {@code tenant_id}, so the restrictive tenant policy is served by the index
 * (the planner reads {@code tenant_id = (SELECT shared.rls_tenant_id())} as the leading condition). A single-column
 * lookup index on a foreign key or a reference column (an item, a line) is exempt.
 */
class TenantIndexesTest {

    @Test
    void noCompositeOrStoreIndexOfTheFourModulesLeadsWithoutTheTenant() {
        List<String> offenders = OwnerDatabase.shared().jdbc().sql("""
                        SELECT ic.relname FROM pg_catalog.pg_index i
                            JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
                            JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
                            JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                        WHERE n.nspname IN ('catalog', 'promotions', 'reporting', 'audit')
                          AND (i.indnkeyatts >= 2 OR pg_catalog.pg_get_indexdef(i.indexrelid, 1, true) = 'store_id')
                          AND pg_catalog.pg_get_indexdef(i.indexrelid, 1, true) <> 'tenant_id'
                        ORDER BY 1""").query(String.class).list();

        assertThat(offenders).as("indexes whose first column is not tenant_id").isEmpty();
    }
}
