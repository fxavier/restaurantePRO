-- Row-level security (ADR 0039, D167, D168). A store's price list, catalog releases, operational availability, daily
-- dishes and exclusions belong to that store; a quick page or an automatic item with no store is for every store
-- (read in every scope, inserted and updated only by an all-stores one; a store scope can still DELETE it, a gap of
-- the V1008 helper scheduled for S70); an item price and a quick-page entry follow their list and
-- page. The working copy (items, families, units, taxes, menus, macros) is company-wide.
SELECT shared.rls_protect_store_table('catalog.price_list');
SELECT shared.rls_protect_store_table('catalog.configuration_release');
SELECT shared.rls_protect_store_table('catalog.item_availability');
SELECT shared.rls_protect_store_table('catalog.daily_dish');
SELECT shared.rls_protect_store_table('catalog.store_item_exclusion');

SELECT shared.rls_protect_store_or_all_table('catalog.quick_page');
SELECT shared.rls_protect_store_or_all_table('catalog.automatic_item');

-- The child link of a price is its list. The only foreign key so far is (price_list_id, currency), which also pins the
-- currency; this single-column one states the parent link the policy and its RS002 trigger guard. Redundant, so it
-- changes no rule.
ALTER TABLE catalog.item_price
    ADD CONSTRAINT item_price_list_fk FOREIGN KEY (price_list_id) REFERENCES catalog.price_list (id);
SELECT shared.rls_protect_child_table('catalog.item_price', 'price_list_id', 'catalog.price_list', 'id');

-- An entry of an every-store page (store_id NULL) is read in every scope, as its page is, and inserted or updated only
-- by an all-stores scope (DELETE follows the read rule, as for its page: pending S70). The canonical CHILD policy would hide those entries from every store scope (VIS(NULL) is false),
-- so a store's release would silently lose them; this policy follows its STORE_OR_ALL parent instead (D168 special).
DO
$$
DECLARE
    v_parent text := 'EXISTS (SELECT 1 FROM catalog.quick_page p WHERE p.id = quick_page_entry.page_id AND %s)';
BEGIN
    ALTER TABLE catalog.quick_page_entry ENABLE ROW LEVEL SECURITY;
    ALTER TABLE catalog.quick_page_entry FORCE ROW LEVEL SECURITY;
    EXECUTE format('CREATE POLICY store_or_all_parent_scope ON catalog.quick_page_entry AS PERMISSIVE FOR ALL TO PUBLIC '
                       || 'USING (%s) WITH CHECK (%s)',
                   format(v_parent, '(p.store_id IS NULL OR ' || shared.rls_visible('p.store_id') || ')'),
                   format(v_parent, shared.rls_visible('p.store_id')));
END;
$$;
CREATE TRIGGER parent_fixed
    BEFORE UPDATE OF page_id
    ON catalog.quick_page_entry
    FOR EACH ROW
    WHEN (OLD.page_id IS DISTINCT FROM NEW.page_id)
EXECUTE FUNCTION shared.rls_reject_scope_change();
SELECT shared.rls_register_protected('catalog.quick_page_entry');

SELECT shared.rls_register_unscoped('catalog.unit', 'Company-wide catalog working copy');
SELECT shared.rls_register_unscoped('catalog.family', 'Company-wide catalog working copy');
SELECT shared.rls_register_unscoped('catalog.item', 'Company-wide catalog working copy');
SELECT shared.rls_register_unscoped('catalog.item_variant', 'Company-wide catalog working copy');
SELECT shared.rls_register_unscoped('catalog.barcode', 'Company-wide catalog working copy');
SELECT shared.rls_register_unscoped('catalog.tax_category', 'Company-wide catalog working copy');
SELECT shared.rls_register_unscoped('catalog.tax_rate', 'Company-wide catalog working copy');
SELECT shared.rls_register_unscoped('catalog.menu_group', 'Company-wide catalog working copy');
SELECT shared.rls_register_unscoped('catalog.menu_option', 'Company-wide catalog working copy');
SELECT shared.rls_register_unscoped('catalog.macro', 'Company-wide catalog working copy');
SELECT shared.rls_register_unscoped('catalog.macro_entry', 'Company-wide catalog working copy');
