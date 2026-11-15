-- Row-level security for inventory (ADR 0039, D167, D168): 16 STORE tables (warehouse with open reads), the
-- two-store stock_transfer and its lines (SPECIAL), 6 CHILD tables through the V1008 helper, and 10 company-wide
-- tables registered UNSCOPED. 24 protected, 10 registered.

-- STORE: the row belongs to one store.
SELECT shared.rls_protect_store_table('inventory.consumption_route');
SELECT shared.rls_protect_store_table('inventory.inventory_activation');
SELECT shared.rls_protect_store_table('inventory.stock_movement');
SELECT shared.rls_protect_store_table('inventory.stock_balance');
SELECT shared.rls_protect_store_table('inventory.balance_check');
SELECT shared.rls_protect_store_table('inventory.consumed_line');
SELECT shared.rls_protect_store_table('inventory.line_void');
SELECT shared.rls_protect_store_table('inventory.waste_record');
SELECT shared.rls_protect_store_table('inventory.consumption_exception');
SELECT shared.rls_protect_store_table('inventory.cost_variance');
SELECT shared.rls_protect_store_table('inventory.preparation');
SELECT shared.rls_protect_store_table('inventory.purchase_order');
SELECT shared.rls_protect_store_table('inventory.goods_receipt');
SELECT shared.rls_protect_store_table('inventory.supplier_return');
SELECT shared.rls_protect_store_table('inventory.stock_count');

-- warehouse (SPECIAL policy, STORE class): every scope reads it, because a transfer names a warehouse of another
-- store and warehouse codes and names are configuration, not facts (D168); only its store's scope writes it, and it
-- never moves to another store.
ALTER TABLE inventory.warehouse ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.warehouse FORCE ROW LEVEL SECURITY;
DO
$$
BEGIN
    EXECUTE 'CREATE POLICY warehouse_read ON inventory.warehouse AS PERMISSIVE FOR SELECT TO PUBLIC USING (true)';
    EXECUTE format('CREATE POLICY store_scope_insert ON inventory.warehouse AS PERMISSIVE FOR INSERT TO PUBLIC '
                       || 'WITH CHECK (%s)', shared.rls_visible('store_id'));
    EXECUTE format('CREATE POLICY store_scope_update ON inventory.warehouse AS PERMISSIVE FOR UPDATE TO PUBLIC '
                       || 'USING (%s) WITH CHECK (%s)', shared.rls_visible('store_id'), shared.rls_visible('store_id'));
    EXECUTE format('CREATE POLICY store_scope_delete ON inventory.warehouse AS PERMISSIVE FOR DELETE TO PUBLIC '
                       || 'USING (%s)', shared.rls_visible('store_id'));
END;
$$;
CREATE TRIGGER store_scope_fixed
    BEFORE UPDATE OF store_id
    ON inventory.warehouse
    FOR EACH ROW
    WHEN (OLD.store_id IS DISTINCT FROM NEW.store_id)
EXECUTE FUNCTION shared.rls_reject_scope_change();
SELECT shared.rls_register_protected('inventory.warehouse');

-- stock_transfer (SPECIAL): seen and written at either end, so a store-A shipper and a store-B receiver each see the
-- transfer (S42, D120); neither end ever changes.
ALTER TABLE inventory.stock_transfer ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_transfer FORCE ROW LEVEL SECURITY;
DO
$$
DECLARE
    v_either_end text := format('(%s OR %s)', shared.rls_visible('from_store_id'), shared.rls_visible('to_store_id'));
BEGIN
    EXECUTE format('CREATE POLICY transfer_scope ON inventory.stock_transfer AS PERMISSIVE FOR ALL TO PUBLIC '
                       || 'USING %s WITH CHECK %s', v_either_end, v_either_end);
END;
$$;
CREATE TRIGGER store_scope_fixed
    BEFORE UPDATE OF from_store_id, to_store_id
    ON inventory.stock_transfer
    FOR EACH ROW
    WHEN (OLD.from_store_id IS DISTINCT FROM NEW.from_store_id OR OLD.to_store_id IS DISTINCT FROM NEW.to_store_id)
EXECUTE FUNCTION shared.rls_reject_scope_change();
SELECT shared.rls_register_protected('inventory.stock_transfer');

-- stock_transfer_line (CHILD of the two-store transfer): the V1008 child helper needs a parent with one store_id, so
-- its parent lookup is written here with the transfer's either-end test; the parent key never changes.
ALTER TABLE inventory.stock_transfer_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_transfer_line FORCE ROW LEVEL SECURITY;
DO
$$
DECLARE
    v_visible text := format('EXISTS (SELECT 1 FROM inventory.stock_transfer p '
                                 || 'WHERE p.id = stock_transfer_line.transfer_id AND (%s OR %s))',
                             shared.rls_visible('p.from_store_id'), shared.rls_visible('p.to_store_id'));
BEGIN
    EXECUTE format('CREATE POLICY parent_scope ON inventory.stock_transfer_line AS PERMISSIVE FOR ALL TO PUBLIC '
                       || 'USING (%s) WITH CHECK (%s)', v_visible, v_visible);
END;
$$;
CREATE TRIGGER parent_fixed
    BEFORE UPDATE OF transfer_id
    ON inventory.stock_transfer_line
    FOR EACH ROW
    WHEN (OLD.transfer_id IS DISTINCT FROM NEW.transfer_id)
EXECUTE FUNCTION shared.rls_reject_scope_change();
SELECT shared.rls_register_protected('inventory.stock_transfer_line');

-- CHILD: visible when the parent's store is in scope (the store is checked on the parent itself, D168).
SELECT shared.rls_protect_child_table('inventory.stock_level', 'warehouse_id', 'inventory.warehouse', 'id');
SELECT shared.rls_protect_child_table('inventory.purchase_order_line', 'purchase_order_id', 'inventory.purchase_order',
                                      'id');
SELECT shared.rls_protect_child_table('inventory.goods_receipt_line', 'receipt_id', 'inventory.goods_receipt', 'id');
SELECT shared.rls_protect_child_table('inventory.receipt_cost_correction', 'receipt_id', 'inventory.goods_receipt',
                                      'id');
SELECT shared.rls_protect_child_table('inventory.supplier_return_line', 'return_id', 'inventory.supplier_return', 'id');
SELECT shared.rls_protect_child_table('inventory.stock_count_line', 'count_id', 'inventory.stock_count', 'id');

-- UNSCOPED: company-wide (r4 §13, D114, D123): items, units and packs, suppliers, recipes and the consumption policy
-- are shared by every store.
SELECT shared.rls_register_unscoped('inventory.stock_unit', 'Stock units: a seeded, company-wide code list (D114)');
SELECT shared.rls_register_unscoped('inventory.stock_item', 'Stock items are company-wide (D114, P38)');
SELECT shared.rls_register_unscoped('inventory.stock_item_pack', 'Packs of a company-wide stock item (D114)');
SELECT shared.rls_register_unscoped('inventory.supplier', 'Suppliers are company-wide (D123)');
SELECT shared.rls_register_unscoped('inventory.supplier_item', 'What a company-wide supplier sells (D123)');
SELECT shared.rls_register_unscoped('inventory.recipe', 'Recipes are company-wide (r4 §13)');
SELECT shared.rls_register_unscoped('inventory.recipe_version', 'Versions of a company-wide recipe (r4 §13)');
SELECT shared.rls_register_unscoped('inventory.recipe_ingredient', 'Ingredients of a company-wide recipe (r4 §13)');
SELECT shared.rls_register_unscoped('inventory.consumption_policy_version',
                                    'The consumption policy is company-wide (D116)');
SELECT shared.rls_register_unscoped('inventory.consumption_policy_rule',
                                    'Rules of the company-wide consumption policy (D116)');

-- The D42 probe of a transfer (D168): its two stores, seen from any scope, so another store's transfer still answers
-- 403, not 404. Returns only the two store ids of that row (or no row). With shared.store_of, the only SECURITY
-- DEFINER functions (RowLevelSecurityCoverageTest's allow-list).
CREATE FUNCTION inventory.stock_transfer_stores(p_id uuid)
    RETURNS TABLE
            (
                from_store_id uuid,
                to_store_id   uuid
            )
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
SELECT t.from_store_id, t.to_store_id
FROM inventory.stock_transfer t
WHERE t.id = p_id
$$;
