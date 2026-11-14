package com.xavier.backend.config.persistence;

import com.xavier.backend.OwnerDatabase;
import java.util.UUID;

/**
 * A throwaway schema with one table protected by {@code shared.rls_protect_store_table}, created and dropped by the
 * owner, for the store-scope suites (no module table has a policy in S64). Rows are seeded by the owner, which
 * bypasses row-level security.
 */
final class RlsScratch implements AutoCloseable {

    private final String schema;

    private RlsScratch(String schema) {
        this.schema = schema;
    }

    static RlsScratch create(String name) {
        RlsScratch scratch = new RlsScratch("rls_scratch_" + name);
        scratch.drop();
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("CREATE SCHEMA " + scratch.schema).update();
            owner.sql("CREATE TABLE " + scratch.table() + " (id uuid PRIMARY KEY, store_id uuid NOT NULL, label text)")
                    .update();
            owner.sql("SELECT shared.rls_protect_store_table(CAST(? AS regclass))").param(scratch.table())
                    .query().listOfRows();
        });
        return scratch;
    }

    String table() {
        return schema + ".item";
    }

    String schema() {
        return schema;
    }

    UUID insert(UUID store, String label) {
        UUID id = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("INSERT INTO " + table() + " (id, store_id, label) VALUES (?, ?, ?)")
                .param(id).param(store).param(label).update();
        return id;
    }

    @Override
    public void close() {
        drop();
    }

    private void drop() {
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("DELETE FROM shared.rls_unscoped_table WHERE table_name::text LIKE ?").param(schema + ".%")
                    .update();
            owner.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        });
    }
}
