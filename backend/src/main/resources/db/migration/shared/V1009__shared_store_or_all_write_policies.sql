-- STORE_OR_ALL writes (D168 amended by S70). V1008's helper gave these tables one FOR ALL policy whose USING was the
-- read rule (store_id IS NULL OR VIS), so a store scope could DELETE an every-store row (and the foreign-key cascade
-- from a page to its entries bypasses RLS). Reads keep that rule; INSERT, UPDATE and DELETE now need VIS(store_id),
-- so only an all-stores scope writes or deletes an every-store row. One policy per command, so no write policy can
-- borrow the read rule.

CREATE OR REPLACE FUNCTION shared.rls_protect_store_or_all_table(p_table regclass) RETURNS void
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM shared.rls_require_store_column(p_table);
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', p_table);
    EXECUTE format('CREATE POLICY store_or_all_read ON %s AS PERMISSIVE FOR SELECT TO PUBLIC '
                       || 'USING (store_id IS NULL OR %s)', p_table, shared.rls_visible('store_id'));
    EXECUTE format('CREATE POLICY store_or_all_insert ON %s AS PERMISSIVE FOR INSERT TO PUBLIC WITH CHECK (%s)',
                   p_table, shared.rls_visible('store_id'));
    EXECUTE format('CREATE POLICY store_or_all_update ON %s AS PERMISSIVE FOR UPDATE TO PUBLIC USING (%s) '
                       || 'WITH CHECK (%s)', p_table, shared.rls_visible('store_id'), shared.rls_visible('store_id'));
    EXECUTE format('CREATE POLICY store_or_all_delete ON %s AS PERMISSIVE FOR DELETE TO PUBLIC USING (%s)',
                   p_table, shared.rls_visible('store_id'));
    EXECUTE format('CREATE TRIGGER store_scope_fixed BEFORE UPDATE OF store_id ON %s FOR EACH ROW '
                       || 'WHEN (OLD.store_id IS DISTINCT FROM NEW.store_id) '
                       || 'EXECUTE FUNCTION shared.rls_reject_scope_change()', p_table);
    PERFORM shared.rls_register_protected(p_table);
END;
$$;

-- STORE_OR_ALL CHILD: no store_id; it follows a STORE_OR_ALL parent of the same module (a quick page's entries).
-- Read when the parent is every-store or in scope; written only when the parent is in scope (VIS(p.store_id)), so an
-- every-store parent's children are written by an all-stores scope only. The canonical CHILD policy would hide the
-- children of an every-store parent from every store scope.
CREATE FUNCTION shared.rls_protect_store_or_all_child_table(p_table regclass, p_fk_column name, p_parent regclass,
                                                            p_parent_key name) RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    v_parent text;
    v_read   text;
    v_write  text;
BEGIN
    PERFORM shared.rls_require_store_column(p_parent);
    v_parent := format('EXISTS (SELECT 1 FROM %s p WHERE p.%I = %s.%I AND %%s)', p_parent, p_parent_key, p_table,
                       p_fk_column);
    v_read := format(v_parent, '(p.store_id IS NULL OR ' || shared.rls_visible('p.store_id') || ')');
    v_write := format(v_parent, shared.rls_visible('p.store_id'));
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', p_table);
    EXECUTE format('CREATE POLICY store_or_all_parent_read ON %s AS PERMISSIVE FOR SELECT TO PUBLIC USING (%s)',
                   p_table, v_read);
    EXECUTE format('CREATE POLICY store_or_all_parent_insert ON %s AS PERMISSIVE FOR INSERT TO PUBLIC '
                       || 'WITH CHECK (%s)', p_table, v_write);
    EXECUTE format('CREATE POLICY store_or_all_parent_update ON %s AS PERMISSIVE FOR UPDATE TO PUBLIC USING (%s) '
                       || 'WITH CHECK (%s)', p_table, v_write, v_write);
    EXECUTE format('CREATE POLICY store_or_all_parent_delete ON %s AS PERMISSIVE FOR DELETE TO PUBLIC USING (%s)',
                   p_table, v_write);
    EXECUTE format('CREATE TRIGGER parent_fixed BEFORE UPDATE OF %I ON %s FOR EACH ROW '
                       || 'WHEN (OLD.%I IS DISTINCT FROM NEW.%I) '
                       || 'EXECUTE FUNCTION shared.rls_reject_scope_change()', p_fk_column, p_table, p_fk_column,
                   p_fk_column);
    PERFORM shared.rls_register_protected(p_table);
END;
$$;

-- Re-protect every table the old helper already protected (a database that applied V5007 before this migration; on a
-- fresh one Flyway runs this first and V5007 uses the new helper).
DO
$$
DECLARE
    v_table regclass;
BEGIN
    FOR v_table IN SELECT polrelid::regclass FROM pg_catalog.pg_policy WHERE polname = 'store_or_all_scope'
        LOOP
            EXECUTE format('DROP POLICY store_or_all_scope ON %s', v_table);
            EXECUTE format('DROP TRIGGER store_scope_fixed ON %s', v_table);
            PERFORM shared.rls_protect_store_or_all_table(v_table);
        END LOOP;
END;
$$;
