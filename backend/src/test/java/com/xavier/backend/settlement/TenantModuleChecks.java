package com.xavier.backend.settlement;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import java.util.HashMap;
import java.util.Map;

/** Tenant-aware views of the D167 classification for the converted modules' RLS tests. */
public final class TenantModuleChecks {

    private TenantModuleChecks() {
    }

    /**
     * {@link AppRoleSession#classification} with the R6 tenant policy folded in: the restrictive {@code tenant_scope}
     * sits on top of the D167 store / parent class (it is dropped from the label), a table with only the tenant
     * policies is {@code TENANT}, and a child's parent is read from its composite {@code (tenant_id, fk)} foreign key
     * (the classification only knows a single-column one).
     */
    public static Map<String, String> classification(String schema) {
        Map<String, String> classes = new HashMap<>();
        AppRoleSession.classification(schema).forEach((table, label) -> classes.put(table,
                label.contains("tenant_rows") ? "TENANT"
                        : label.replace("+OTHER:tenant_scope", "").replace("OTHER:tenant_scope+", "")));
        classes.replaceAll((table, label) -> label.equals("CHILD:?") ? "CHILD:" + parentOf(schema, table) : label);
        return classes;
    }

    private static String parentOf(String schema, String table) {
        return OwnerDatabase.shared().jdbc().sql("""
                SELECT k.confrelid::regclass::text FROM pg_catalog.pg_trigger t
                    JOIN pg_catalog.pg_constraint k ON k.conrelid = t.tgrelid AND k.contype = 'f'
                        AND k.conkey @> ARRAY[t.tgattr[0]]
                WHERE t.tgrelid = CAST(? AS regclass) AND t.tgname = 'parent_fixed'""")
                .param(schema + "." + table).query(String.class).single();
    }
}
