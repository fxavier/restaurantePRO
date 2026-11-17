package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D180 "Indexes": in a tenant table, tenant_id leads every composite index that used to lead with store_id. */
class TenantIndexesTest {

    @Test
    void noIndexOfSettlementCashOrFiscalLeadsWithStoreId() {
        List<String> leading = OwnerDatabase.shared().jdbc().sql("""
                SELECT ic.relname FROM pg_catalog.pg_index i
                    JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
                    JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                    JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = i.indkey[0]
                WHERE n.nspname IN ('settlement', 'cash', 'fiscal') AND a.attname = 'store_id'
                ORDER BY 1""").query(String.class).list();

        assertThat(leading).isEmpty();
    }
}
