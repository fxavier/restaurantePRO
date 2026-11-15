-- R6 S74c: inventory becomes tenant-isolated (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- 33 tables get tenant_id and the restrictive tenant_scope policy, ANDed with the store policies V13081 wrote: the
-- 15 STORE tables, warehouse and the two-store transfer and its lines (SPECIAL), the 6 CHILD tables, and the nine
-- company-wide tables that were UNSCOPED (stock items and packs, suppliers and what they sell, recipes, the
-- consumption policy), which become TENANT class (permissive tenant_rows + restrictive tenant_scope). stock_unit,
-- the seeded code list of units (D114, identical for every tenant, written by migrations only), is PLATFORM.
-- No UPDATE runs: every new column takes a constant default (catalog-only, no rewrite), then the database default
-- shared.rls_tenant_id(), so an insert takes the bound tenant and an unbound one raises RS003. The bootstrap tenant
-- is the fixed literal of every R6 migration and of TenantTestSupport.BOOTSTRAP. Data of the single existing
-- installation stays in the bootstrap tenant; the seeded consumption policy version 1 too (a new tenant starts with
-- no policy version, which the planner already accepts).

-- (1)+(2) tenant_id on every table.
ALTER TABLE inventory.stock_item
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.stock_item
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.stock_item_pack
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.stock_item_pack
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.warehouse
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.warehouse
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.consumption_route
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.consumption_route
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.stock_movement
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.stock_movement
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.stock_transfer
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.stock_transfer
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.consumption_exception
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.consumption_exception
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.recipe
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.recipe
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.recipe_version
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.recipe_version
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.recipe_ingredient
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.recipe_ingredient
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.consumption_policy_version
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.consumption_policy_version
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.consumption_policy_rule
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.consumption_policy_rule
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.preparation
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.preparation
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.waste_record
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.waste_record
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.supplier
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.supplier
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.supplier_item
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.supplier_item
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.purchase_order
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.purchase_order
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.purchase_order_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.purchase_order_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.goods_receipt
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.goods_receipt
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.goods_receipt_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.goods_receipt_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.supplier_return
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.supplier_return
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.supplier_return_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.supplier_return_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.receipt_cost_correction
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.receipt_cost_correction
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.cost_variance
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.cost_variance
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.stock_count
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.stock_count
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.stock_count_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.stock_count_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.stock_level
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.stock_level
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.inventory_activation
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.inventory_activation
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.stock_balance
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.stock_balance
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.stock_transfer_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.stock_transfer_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.balance_check
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.balance_check
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.consumed_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.consumed_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE inventory.line_void
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE inventory.line_void
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) UNIQUE (tenant_id, id) on every table with an id, parents before children reference them; a table keyed
-- by its columns (no id) leads its primary key with tenant_id instead. warehouse also gets (tenant_id, id, store_id),
-- the target of the balance's store-consistency key (V13080).
ALTER TABLE inventory.stock_item
    ADD CONSTRAINT stock_item_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.stock_item_pack
    ADD CONSTRAINT stock_item_pack_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.warehouse
    ADD CONSTRAINT warehouse_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.consumption_route
    ADD CONSTRAINT consumption_route_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT stock_movement_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.stock_transfer
    ADD CONSTRAINT stock_transfer_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.consumption_exception
    ADD CONSTRAINT consumption_exception_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.recipe
    ADD CONSTRAINT recipe_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.recipe_version
    ADD CONSTRAINT recipe_version_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.recipe_ingredient
    ADD CONSTRAINT recipe_ingredient_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.consumption_policy_version
    ADD CONSTRAINT consumption_policy_version_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.consumption_policy_rule
    ADD CONSTRAINT consumption_policy_rule_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.preparation
    ADD CONSTRAINT preparation_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.waste_record
    ADD CONSTRAINT waste_record_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.supplier
    ADD CONSTRAINT supplier_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.supplier_item
    ADD CONSTRAINT supplier_item_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.purchase_order
    ADD CONSTRAINT purchase_order_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.purchase_order_line
    ADD CONSTRAINT purchase_order_line_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.goods_receipt
    ADD CONSTRAINT goods_receipt_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.goods_receipt_line
    ADD CONSTRAINT goods_receipt_line_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.supplier_return
    ADD CONSTRAINT supplier_return_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.supplier_return_line
    ADD CONSTRAINT supplier_return_line_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.receipt_cost_correction
    ADD CONSTRAINT receipt_cost_correction_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.cost_variance
    ADD CONSTRAINT cost_variance_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.stock_count
    ADD CONSTRAINT stock_count_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.stock_count_line
    ADD CONSTRAINT stock_count_line_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE inventory.warehouse
    ADD CONSTRAINT warehouse_tenant_id_store_uk UNIQUE (tenant_id, id, store_id);
ALTER TABLE inventory.stock_level
    DROP CONSTRAINT stock_level_pk,
    ADD CONSTRAINT stock_level_pk PRIMARY KEY (tenant_id, warehouse_id, stock_item_id);
ALTER TABLE inventory.inventory_activation
    DROP CONSTRAINT inventory_activation_pk,
    ADD CONSTRAINT inventory_activation_pk PRIMARY KEY (tenant_id, store_id);
ALTER TABLE inventory.stock_balance
    DROP CONSTRAINT stock_balance_pk,
    ADD CONSTRAINT stock_balance_pk PRIMARY KEY (tenant_id, warehouse_id, stock_item_id);
ALTER TABLE inventory.stock_transfer_line
    DROP CONSTRAINT stock_transfer_line_pk,
    ADD CONSTRAINT stock_transfer_line_pk PRIMARY KEY (tenant_id, transfer_id, stock_item_id);
ALTER TABLE inventory.balance_check
    DROP CONSTRAINT balance_check_pk,
    ADD CONSTRAINT balance_check_pk PRIMARY KEY (tenant_id, store_id);
ALTER TABLE inventory.consumed_line
    DROP CONSTRAINT consumed_line_pk,
    ADD CONSTRAINT consumed_line_pk PRIMARY KEY (tenant_id, line_id);
ALTER TABLE inventory.line_void
    DROP CONSTRAINT line_void_pk,
    ADD CONSTRAINT line_void_pk PRIMARY KEY (tenant_id, void_id);

-- (4) Business unique keys per tenant: create the (tenant_id, ...) one, drop the old, keep the old name (the
-- application maps a violation by constraint name, UniqueConstraints, and ON CONFLICT targets name the columns).
ALTER TABLE inventory.stock_item
    ADD CONSTRAINT stock_item_code_uk_tenant UNIQUE (tenant_id, code);
ALTER TABLE inventory.stock_item
    DROP CONSTRAINT stock_item_code_uk;
ALTER TABLE inventory.stock_item
    RENAME CONSTRAINT stock_item_code_uk_tenant TO stock_item_code_uk;
ALTER TABLE inventory.stock_item_pack
    ADD CONSTRAINT stock_item_pack_code_uk_tenant UNIQUE (tenant_id, stock_item_id, code);
ALTER TABLE inventory.stock_item_pack
    DROP CONSTRAINT stock_item_pack_code_uk;
ALTER TABLE inventory.stock_item_pack
    RENAME CONSTRAINT stock_item_pack_code_uk_tenant TO stock_item_pack_code_uk;
ALTER TABLE inventory.warehouse
    ADD CONSTRAINT warehouse_store_code_uk_tenant UNIQUE (tenant_id, store_id, code);
ALTER TABLE inventory.warehouse
    DROP CONSTRAINT warehouse_store_code_uk;
ALTER TABLE inventory.warehouse
    RENAME CONSTRAINT warehouse_store_code_uk_tenant TO warehouse_store_code_uk;
ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT stock_movement_posting_seq_uk_tenant UNIQUE (tenant_id, posting_seq);
ALTER TABLE inventory.stock_movement
    DROP CONSTRAINT stock_movement_posting_seq_uk;
ALTER TABLE inventory.stock_movement
    RENAME CONSTRAINT stock_movement_posting_seq_uk_tenant TO stock_movement_posting_seq_uk;
ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT stock_movement_source_uk_tenant UNIQUE (tenant_id, source_type, source_id, stock_item_id, type, warehouse_id);
ALTER TABLE inventory.stock_movement
    DROP CONSTRAINT stock_movement_source_uk;
ALTER TABLE inventory.stock_movement
    RENAME CONSTRAINT stock_movement_source_uk_tenant TO stock_movement_source_uk;
ALTER TABLE inventory.consumption_exception
    ADD CONSTRAINT consumption_exception_source_uk_tenant UNIQUE (tenant_id, source_type, source_id, kind);
ALTER TABLE inventory.consumption_exception
    DROP CONSTRAINT consumption_exception_source_uk;
ALTER TABLE inventory.consumption_exception
    RENAME CONSTRAINT consumption_exception_source_uk_tenant TO consumption_exception_source_uk;
ALTER TABLE inventory.recipe_version
    ADD CONSTRAINT recipe_version_no_uk_tenant UNIQUE (tenant_id, recipe_id, version_no);
ALTER TABLE inventory.recipe_version
    DROP CONSTRAINT recipe_version_no_uk;
ALTER TABLE inventory.recipe_version
    RENAME CONSTRAINT recipe_version_no_uk_tenant TO recipe_version_no_uk;
ALTER TABLE inventory.consumption_policy_version
    ADD CONSTRAINT consumption_policy_version_no_uk_tenant UNIQUE (tenant_id, version_no);
ALTER TABLE inventory.consumption_policy_version
    DROP CONSTRAINT consumption_policy_version_no_uk;
ALTER TABLE inventory.consumption_policy_version
    RENAME CONSTRAINT consumption_policy_version_no_uk_tenant TO consumption_policy_version_no_uk;
ALTER TABLE inventory.waste_record
    ADD CONSTRAINT waste_record_source_uk_tenant UNIQUE (tenant_id, void_id, original_movement_id);
ALTER TABLE inventory.waste_record
    DROP CONSTRAINT waste_record_source_uk;
ALTER TABLE inventory.waste_record
    RENAME CONSTRAINT waste_record_source_uk_tenant TO waste_record_source_uk;
ALTER TABLE inventory.supplier
    ADD CONSTRAINT supplier_code_uk_tenant UNIQUE (tenant_id, code);
ALTER TABLE inventory.supplier
    DROP CONSTRAINT supplier_code_uk;
ALTER TABLE inventory.supplier
    RENAME CONSTRAINT supplier_code_uk_tenant TO supplier_code_uk;
ALTER TABLE inventory.purchase_order
    ADD CONSTRAINT purchase_order_number_uk_tenant UNIQUE (tenant_id, store_id, number);
ALTER TABLE inventory.purchase_order
    DROP CONSTRAINT purchase_order_number_uk;
ALTER TABLE inventory.purchase_order
    RENAME CONSTRAINT purchase_order_number_uk_tenant TO purchase_order_number_uk;
ALTER TABLE inventory.purchase_order_line
    ADD CONSTRAINT purchase_order_line_item_uk_tenant UNIQUE (tenant_id, purchase_order_id, stock_item_id);
ALTER TABLE inventory.purchase_order_line
    DROP CONSTRAINT purchase_order_line_item_uk;
ALTER TABLE inventory.purchase_order_line
    RENAME CONSTRAINT purchase_order_line_item_uk_tenant TO purchase_order_line_item_uk;
ALTER TABLE inventory.goods_receipt_line
    ADD CONSTRAINT goods_receipt_line_item_uk_tenant UNIQUE (tenant_id, receipt_id, stock_item_id);
ALTER TABLE inventory.goods_receipt_line
    DROP CONSTRAINT goods_receipt_line_item_uk;
ALTER TABLE inventory.goods_receipt_line
    RENAME CONSTRAINT goods_receipt_line_item_uk_tenant TO goods_receipt_line_item_uk;
ALTER TABLE inventory.supplier_return_line
    ADD CONSTRAINT supplier_return_line_item_uk_tenant UNIQUE (tenant_id, return_id, stock_item_id);
ALTER TABLE inventory.supplier_return_line
    DROP CONSTRAINT supplier_return_line_item_uk;
ALTER TABLE inventory.supplier_return_line
    RENAME CONSTRAINT supplier_return_line_item_uk_tenant TO supplier_return_line_item_uk;
ALTER TABLE inventory.receipt_cost_correction
    ADD CONSTRAINT receipt_cost_correction_seq_uk_tenant UNIQUE (tenant_id, receipt_line_id, seq);
ALTER TABLE inventory.receipt_cost_correction
    DROP CONSTRAINT receipt_cost_correction_seq_uk;
ALTER TABLE inventory.receipt_cost_correction
    RENAME CONSTRAINT receipt_cost_correction_seq_uk_tenant TO receipt_cost_correction_seq_uk;
ALTER TABLE inventory.cost_variance
    ADD CONSTRAINT cost_variance_correction_uk_tenant UNIQUE (tenant_id, correction_id);
ALTER TABLE inventory.cost_variance
    DROP CONSTRAINT cost_variance_correction_uk;
ALTER TABLE inventory.cost_variance
    RENAME CONSTRAINT cost_variance_correction_uk_tenant TO cost_variance_correction_uk;
ALTER TABLE inventory.cost_variance
    ADD CONSTRAINT cost_variance_return_line_uk_tenant UNIQUE (tenant_id, return_line_id);
ALTER TABLE inventory.cost_variance
    DROP CONSTRAINT cost_variance_return_line_uk;
ALTER TABLE inventory.cost_variance
    RENAME CONSTRAINT cost_variance_return_line_uk_tenant TO cost_variance_return_line_uk;
ALTER TABLE inventory.stock_count_line
    ADD CONSTRAINT stock_count_line_item_uk_tenant UNIQUE (tenant_id, count_id, stock_item_id);
ALTER TABLE inventory.stock_count_line
    DROP CONSTRAINT stock_count_line_item_uk;
ALTER TABLE inventory.stock_count_line
    RENAME CONSTRAINT stock_count_line_item_uk_tenant TO stock_count_line_item_uk;
CREATE UNIQUE INDEX consumption_route_store_destination_uk_tenant ON inventory.consumption_route (tenant_id, store_id, coalesce(destination_code, ''));
DROP INDEX inventory.consumption_route_store_destination_uk;
ALTER INDEX inventory.consumption_route_store_destination_uk_tenant RENAME TO consumption_route_store_destination_uk;
CREATE UNIQUE INDEX recipe_sale_uk_tenant ON inventory.recipe (tenant_id, item_id, coalesce(variant_id, '00000000-0000-0000-0000-000000000000'))
    WHERE item_id IS NOT NULL;
DROP INDEX inventory.recipe_sale_uk;
ALTER INDEX inventory.recipe_sale_uk_tenant RENAME TO recipe_sale_uk;
CREATE UNIQUE INDEX recipe_preparation_uk_tenant ON inventory.recipe (tenant_id, output_stock_item_id)
    WHERE output_stock_item_id IS NOT NULL;
DROP INDEX inventory.recipe_preparation_uk;
ALTER INDEX inventory.recipe_preparation_uk_tenant RENAME TO recipe_preparation_uk;
CREATE UNIQUE INDEX recipe_version_one_active_uk_tenant ON inventory.recipe_version (tenant_id, recipe_id)
    WHERE status = 'ACTIVE';
DROP INDEX inventory.recipe_version_one_active_uk;
ALTER INDEX inventory.recipe_version_one_active_uk_tenant RENAME TO recipe_version_one_active_uk;
CREATE UNIQUE INDEX consumption_policy_rule_family_uk_tenant ON inventory.consumption_policy_rule (tenant_id, policy_version_id, coalesce(family_id, '00000000-0000-0000-0000-000000000000'));
DROP INDEX inventory.consumption_policy_rule_family_uk;
ALTER INDEX inventory.consumption_policy_rule_family_uk_tenant RENAME TO consumption_policy_rule_family_uk;
CREATE UNIQUE INDEX supplier_item_uk_tenant ON inventory.supplier_item (tenant_id, supplier_id, stock_item_id, coalesce(pack_id, '00000000-0000-0000-0000-000000000000'::uuid));
DROP INDEX inventory.supplier_item_uk;
ALTER INDEX inventory.supplier_item_uk_tenant RENAME TO supplier_item_uk;
CREATE UNIQUE INDEX stock_count_live_ux_tenant ON inventory.stock_count (tenant_id, warehouse_id)
    WHERE status IN ('DRAFT', 'IN_PROGRESS', 'SUBMITTED');
DROP INDEX inventory.stock_count_live_ux;
ALTER INDEX inventory.stock_count_live_ux_tenant RENAME TO stock_count_live_ux;
CREATE UNIQUE INDEX stock_count_opening_ux_tenant ON inventory.stock_count (tenant_id, warehouse_id)
    WHERE kind = 'OPENING' AND status = 'APPROVED';
DROP INDEX inventory.stock_count_opening_ux;
ALTER INDEX inventory.stock_count_opening_ux_tenant RENAME TO stock_count_opening_ux;

-- Indexes that led with a store lead with the tenant now (D179: tenant_id leads, and uuid_eq is leakproof, so the
-- tenant policy stays an index condition, D169); each keeps its name.
CREATE INDEX stock_movement_store_date_ix_tenant ON inventory.stock_movement (tenant_id, store_id, business_date, type);
DROP INDEX inventory.stock_movement_store_date_ix;
ALTER INDEX inventory.stock_movement_store_date_ix_tenant RENAME TO stock_movement_store_date_ix;
CREATE INDEX consumption_exception_open_ix_tenant ON inventory.consumption_exception (tenant_id, store_id)
    WHERE resolved_at IS NULL;
DROP INDEX inventory.consumption_exception_open_ix;
ALTER INDEX inventory.consumption_exception_open_ix_tenant RENAME TO consumption_exception_open_ix;
CREATE INDEX consumption_exception_open_day_ix_tenant ON inventory.consumption_exception (tenant_id, store_id, business_day)
    WHERE resolved_at IS NULL;
DROP INDEX inventory.consumption_exception_open_day_ix;
ALTER INDEX inventory.consumption_exception_open_day_ix_tenant RENAME TO consumption_exception_open_day_ix;
CREATE INDEX consumption_exception_open_confirmed_ix_tenant ON inventory.consumption_exception (tenant_id, store_id, confirmed_at)
    WHERE resolved_at IS NULL;
DROP INDEX inventory.consumption_exception_open_confirmed_ix;
ALTER INDEX inventory.consumption_exception_open_confirmed_ix_tenant RENAME TO consumption_exception_open_confirmed_ix;
CREATE INDEX consumption_exception_store_ix_tenant ON inventory.consumption_exception (tenant_id, store_id, created_at);
DROP INDEX inventory.consumption_exception_store_ix;
ALTER INDEX inventory.consumption_exception_store_ix_tenant RENAME TO consumption_exception_store_ix;
CREATE INDEX preparation_store_ix_tenant ON inventory.preparation (tenant_id, store_id, created_at);
DROP INDEX inventory.preparation_store_ix;
ALTER INDEX inventory.preparation_store_ix_tenant RENAME TO preparation_store_ix;
CREATE INDEX stock_transfer_from_store_ix_tenant ON inventory.stock_transfer (tenant_id, from_store_id, status);
DROP INDEX inventory.stock_transfer_from_store_ix;
ALTER INDEX inventory.stock_transfer_from_store_ix_tenant RENAME TO stock_transfer_from_store_ix;
CREATE INDEX stock_transfer_to_store_ix_tenant ON inventory.stock_transfer (tenant_id, to_store_id, status);
DROP INDEX inventory.stock_transfer_to_store_ix;
ALTER INDEX inventory.stock_transfer_to_store_ix_tenant RENAME TO stock_transfer_to_store_ix;
CREATE INDEX goods_receipt_store_ix_tenant ON inventory.goods_receipt (tenant_id, store_id, status, business_date);
DROP INDEX inventory.goods_receipt_store_ix;
ALTER INDEX inventory.goods_receipt_store_ix_tenant RENAME TO goods_receipt_store_ix;
CREATE INDEX line_void_store_ix_tenant ON inventory.line_void (tenant_id, store_id);
DROP INDEX inventory.line_void_store_ix;
ALTER INDEX inventory.line_void_store_ix_tenant RENAME TO line_void_store_ix;
CREATE INDEX waste_record_store_date_ix_tenant ON inventory.waste_record (tenant_id, store_id, business_date);
DROP INDEX inventory.waste_record_store_date_ix;
ALTER INDEX inventory.waste_record_store_date_ix_tenant RENAME TO waste_record_store_date_ix;
CREATE INDEX purchase_order_store_ix_tenant ON inventory.purchase_order (tenant_id, store_id, status);
DROP INDEX inventory.purchase_order_store_ix;
ALTER INDEX inventory.purchase_order_store_ix_tenant RENAME TO purchase_order_store_ix;
CREATE INDEX supplier_return_store_ix_tenant ON inventory.supplier_return (tenant_id, store_id, status);
DROP INDEX inventory.supplier_return_store_ix;
ALTER INDEX inventory.supplier_return_store_ix_tenant RENAME TO supplier_return_store_ix;
CREATE INDEX cost_variance_store_date_ix_tenant ON inventory.cost_variance (tenant_id, store_id, business_date);
DROP INDEX inventory.cost_variance_store_date_ix;
ALTER INDEX inventory.cost_variance_store_date_ix_tenant RENAME TO cost_variance_store_date_ix;
CREATE INDEX stock_count_store_ix_tenant ON inventory.stock_count (tenant_id, store_id, created_at);
DROP INDEX inventory.stock_count_store_ix;
ALTER INDEX inventory.stock_count_store_ix_tenant RENAME TO stock_count_store_ix;
CREATE UNIQUE INDEX consumption_policy_one_active_uk_tenant ON inventory.consumption_policy_version (tenant_id)
    WHERE status = 'ACTIVE';
DROP INDEX inventory.consumption_policy_one_active_uk;
ALTER INDEX inventory.consumption_policy_one_active_uk_tenant RENAME TO consumption_policy_one_active_uk;

-- (5) In-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, then drop the
-- single-column one and keep its name. The foreign keys to inventory.stock_unit stay: it is PLATFORM, a code list.
ALTER TABLE inventory.stock_item_pack
    ADD CONSTRAINT stock_item_pack_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_item_pack
    VALIDATE CONSTRAINT stock_item_pack_item_fk_tenant;
ALTER TABLE inventory.stock_item_pack
    DROP CONSTRAINT stock_item_pack_item_fk;
ALTER TABLE inventory.stock_item_pack
    RENAME CONSTRAINT stock_item_pack_item_fk_tenant TO stock_item_pack_item_fk;
ALTER TABLE inventory.consumption_route
    ADD CONSTRAINT consumption_route_warehouse_fk_tenant FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.consumption_route
    VALIDATE CONSTRAINT consumption_route_warehouse_fk_tenant;
ALTER TABLE inventory.consumption_route
    DROP CONSTRAINT consumption_route_warehouse_fk;
ALTER TABLE inventory.consumption_route
    RENAME CONSTRAINT consumption_route_warehouse_fk_tenant TO consumption_route_warehouse_fk;
ALTER TABLE inventory.stock_level
    ADD CONSTRAINT stock_level_warehouse_fk_tenant FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_level
    VALIDATE CONSTRAINT stock_level_warehouse_fk_tenant;
ALTER TABLE inventory.stock_level
    DROP CONSTRAINT stock_level_warehouse_fk;
ALTER TABLE inventory.stock_level
    RENAME CONSTRAINT stock_level_warehouse_fk_tenant TO stock_level_warehouse_fk;
ALTER TABLE inventory.stock_level
    ADD CONSTRAINT stock_level_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_level
    VALIDATE CONSTRAINT stock_level_item_fk_tenant;
ALTER TABLE inventory.stock_level
    DROP CONSTRAINT stock_level_item_fk;
ALTER TABLE inventory.stock_level
    RENAME CONSTRAINT stock_level_item_fk_tenant TO stock_level_item_fk;
ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT stock_movement_warehouse_fk_tenant FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_movement
    VALIDATE CONSTRAINT stock_movement_warehouse_fk_tenant;
ALTER TABLE inventory.stock_movement
    DROP CONSTRAINT stock_movement_warehouse_fk;
ALTER TABLE inventory.stock_movement
    RENAME CONSTRAINT stock_movement_warehouse_fk_tenant TO stock_movement_warehouse_fk;
ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT stock_movement_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_movement
    VALIDATE CONSTRAINT stock_movement_item_fk_tenant;
ALTER TABLE inventory.stock_movement
    DROP CONSTRAINT stock_movement_item_fk;
ALTER TABLE inventory.stock_movement
    RENAME CONSTRAINT stock_movement_item_fk_tenant TO stock_movement_item_fk;
ALTER TABLE inventory.stock_balance
    ADD CONSTRAINT stock_balance_warehouse_fk_tenant FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_balance
    VALIDATE CONSTRAINT stock_balance_warehouse_fk_tenant;
ALTER TABLE inventory.stock_balance
    DROP CONSTRAINT stock_balance_warehouse_fk;
ALTER TABLE inventory.stock_balance
    RENAME CONSTRAINT stock_balance_warehouse_fk_tenant TO stock_balance_warehouse_fk;
ALTER TABLE inventory.stock_balance
    ADD CONSTRAINT stock_balance_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_balance
    VALIDATE CONSTRAINT stock_balance_item_fk_tenant;
ALTER TABLE inventory.stock_balance
    DROP CONSTRAINT stock_balance_item_fk;
ALTER TABLE inventory.stock_balance
    RENAME CONSTRAINT stock_balance_item_fk_tenant TO stock_balance_item_fk;
ALTER TABLE inventory.stock_balance
    ADD CONSTRAINT stock_balance_warehouse_store_fk_tenant FOREIGN KEY (tenant_id, warehouse_id, store_id)
        REFERENCES inventory.warehouse (tenant_id, id, store_id) NOT VALID;
ALTER TABLE inventory.stock_balance
    VALIDATE CONSTRAINT stock_balance_warehouse_store_fk_tenant;
ALTER TABLE inventory.stock_balance
    DROP CONSTRAINT stock_balance_warehouse_store_fk;
ALTER TABLE inventory.stock_balance
    RENAME CONSTRAINT stock_balance_warehouse_store_fk_tenant TO stock_balance_warehouse_store_fk;
ALTER TABLE inventory.stock_transfer
    ADD CONSTRAINT stock_transfer_from_fk_tenant FOREIGN KEY (tenant_id, from_warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_transfer
    VALIDATE CONSTRAINT stock_transfer_from_fk_tenant;
ALTER TABLE inventory.stock_transfer
    DROP CONSTRAINT stock_transfer_from_fk;
ALTER TABLE inventory.stock_transfer
    RENAME CONSTRAINT stock_transfer_from_fk_tenant TO stock_transfer_from_fk;
ALTER TABLE inventory.stock_transfer
    ADD CONSTRAINT stock_transfer_to_fk_tenant FOREIGN KEY (tenant_id, to_warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_transfer
    VALIDATE CONSTRAINT stock_transfer_to_fk_tenant;
ALTER TABLE inventory.stock_transfer
    DROP CONSTRAINT stock_transfer_to_fk;
ALTER TABLE inventory.stock_transfer
    RENAME CONSTRAINT stock_transfer_to_fk_tenant TO stock_transfer_to_fk;
ALTER TABLE inventory.stock_transfer_line
    ADD CONSTRAINT stock_transfer_line_transfer_fk_tenant FOREIGN KEY (tenant_id, transfer_id)
        REFERENCES inventory.stock_transfer (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_transfer_line
    VALIDATE CONSTRAINT stock_transfer_line_transfer_fk_tenant;
ALTER TABLE inventory.stock_transfer_line
    DROP CONSTRAINT stock_transfer_line_transfer_fk;
ALTER TABLE inventory.stock_transfer_line
    RENAME CONSTRAINT stock_transfer_line_transfer_fk_tenant TO stock_transfer_line_transfer_fk;
ALTER TABLE inventory.stock_transfer_line
    ADD CONSTRAINT stock_transfer_line_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_transfer_line
    VALIDATE CONSTRAINT stock_transfer_line_item_fk_tenant;
ALTER TABLE inventory.stock_transfer_line
    DROP CONSTRAINT stock_transfer_line_item_fk;
ALTER TABLE inventory.stock_transfer_line
    RENAME CONSTRAINT stock_transfer_line_item_fk_tenant TO stock_transfer_line_item_fk;
ALTER TABLE inventory.recipe
    ADD CONSTRAINT recipe_output_fk_tenant FOREIGN KEY (tenant_id, output_stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.recipe
    VALIDATE CONSTRAINT recipe_output_fk_tenant;
ALTER TABLE inventory.recipe
    DROP CONSTRAINT recipe_output_fk;
ALTER TABLE inventory.recipe
    RENAME CONSTRAINT recipe_output_fk_tenant TO recipe_output_fk;
ALTER TABLE inventory.recipe_version
    ADD CONSTRAINT recipe_version_recipe_fk_tenant FOREIGN KEY (tenant_id, recipe_id)
        REFERENCES inventory.recipe (tenant_id, id) NOT VALID;
ALTER TABLE inventory.recipe_version
    VALIDATE CONSTRAINT recipe_version_recipe_fk_tenant;
ALTER TABLE inventory.recipe_version
    DROP CONSTRAINT recipe_version_recipe_fk;
ALTER TABLE inventory.recipe_version
    RENAME CONSTRAINT recipe_version_recipe_fk_tenant TO recipe_version_recipe_fk;
ALTER TABLE inventory.recipe_ingredient
    ADD CONSTRAINT recipe_ingredient_version_fk_tenant FOREIGN KEY (tenant_id, recipe_version_id)
        REFERENCES inventory.recipe_version (tenant_id, id) NOT VALID;
ALTER TABLE inventory.recipe_ingredient
    VALIDATE CONSTRAINT recipe_ingredient_version_fk_tenant;
ALTER TABLE inventory.recipe_ingredient
    DROP CONSTRAINT recipe_ingredient_version_fk;
ALTER TABLE inventory.recipe_ingredient
    RENAME CONSTRAINT recipe_ingredient_version_fk_tenant TO recipe_ingredient_version_fk;
ALTER TABLE inventory.recipe_ingredient
    ADD CONSTRAINT recipe_ingredient_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.recipe_ingredient
    VALIDATE CONSTRAINT recipe_ingredient_item_fk_tenant;
ALTER TABLE inventory.recipe_ingredient
    DROP CONSTRAINT recipe_ingredient_item_fk;
ALTER TABLE inventory.recipe_ingredient
    RENAME CONSTRAINT recipe_ingredient_item_fk_tenant TO recipe_ingredient_item_fk;
ALTER TABLE inventory.consumption_policy_rule
    ADD CONSTRAINT consumption_policy_rule_version_fk_tenant FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES inventory.consumption_policy_version (tenant_id, id) NOT VALID;
ALTER TABLE inventory.consumption_policy_rule
    VALIDATE CONSTRAINT consumption_policy_rule_version_fk_tenant;
ALTER TABLE inventory.consumption_policy_rule
    DROP CONSTRAINT consumption_policy_rule_version_fk;
ALTER TABLE inventory.consumption_policy_rule
    RENAME CONSTRAINT consumption_policy_rule_version_fk_tenant TO consumption_policy_rule_version_fk;
ALTER TABLE inventory.preparation
    ADD CONSTRAINT preparation_warehouse_fk_tenant FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.preparation
    VALIDATE CONSTRAINT preparation_warehouse_fk_tenant;
ALTER TABLE inventory.preparation
    DROP CONSTRAINT preparation_warehouse_fk;
ALTER TABLE inventory.preparation
    RENAME CONSTRAINT preparation_warehouse_fk_tenant TO preparation_warehouse_fk;
ALTER TABLE inventory.preparation
    ADD CONSTRAINT preparation_recipe_version_fk_tenant FOREIGN KEY (tenant_id, recipe_version_id)
        REFERENCES inventory.recipe_version (tenant_id, id) NOT VALID;
ALTER TABLE inventory.preparation
    VALIDATE CONSTRAINT preparation_recipe_version_fk_tenant;
ALTER TABLE inventory.preparation
    DROP CONSTRAINT preparation_recipe_version_fk;
ALTER TABLE inventory.preparation
    RENAME CONSTRAINT preparation_recipe_version_fk_tenant TO preparation_recipe_version_fk;
ALTER TABLE inventory.preparation
    ADD CONSTRAINT preparation_output_fk_tenant FOREIGN KEY (tenant_id, output_stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.preparation
    VALIDATE CONSTRAINT preparation_output_fk_tenant;
ALTER TABLE inventory.preparation
    DROP CONSTRAINT preparation_output_fk;
ALTER TABLE inventory.preparation
    RENAME CONSTRAINT preparation_output_fk_tenant TO preparation_output_fk;
ALTER TABLE inventory.waste_record
    ADD CONSTRAINT waste_record_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.waste_record
    VALIDATE CONSTRAINT waste_record_item_fk_tenant;
ALTER TABLE inventory.waste_record
    DROP CONSTRAINT waste_record_item_fk;
ALTER TABLE inventory.waste_record
    RENAME CONSTRAINT waste_record_item_fk_tenant TO waste_record_item_fk;
ALTER TABLE inventory.supplier_item
    ADD CONSTRAINT supplier_item_supplier_fk_tenant FOREIGN KEY (tenant_id, supplier_id)
        REFERENCES inventory.supplier (tenant_id, id) NOT VALID;
ALTER TABLE inventory.supplier_item
    VALIDATE CONSTRAINT supplier_item_supplier_fk_tenant;
ALTER TABLE inventory.supplier_item
    DROP CONSTRAINT supplier_item_supplier_fk;
ALTER TABLE inventory.supplier_item
    RENAME CONSTRAINT supplier_item_supplier_fk_tenant TO supplier_item_supplier_fk;
ALTER TABLE inventory.supplier_item
    ADD CONSTRAINT supplier_item_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.supplier_item
    VALIDATE CONSTRAINT supplier_item_item_fk_tenant;
ALTER TABLE inventory.supplier_item
    DROP CONSTRAINT supplier_item_item_fk;
ALTER TABLE inventory.supplier_item
    RENAME CONSTRAINT supplier_item_item_fk_tenant TO supplier_item_item_fk;
ALTER TABLE inventory.supplier_item
    ADD CONSTRAINT supplier_item_pack_fk_tenant FOREIGN KEY (tenant_id, pack_id)
        REFERENCES inventory.stock_item_pack (tenant_id, id) NOT VALID;
ALTER TABLE inventory.supplier_item
    VALIDATE CONSTRAINT supplier_item_pack_fk_tenant;
ALTER TABLE inventory.supplier_item
    DROP CONSTRAINT supplier_item_pack_fk;
ALTER TABLE inventory.supplier_item
    RENAME CONSTRAINT supplier_item_pack_fk_tenant TO supplier_item_pack_fk;
ALTER TABLE inventory.purchase_order
    ADD CONSTRAINT purchase_order_warehouse_fk_tenant FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.purchase_order
    VALIDATE CONSTRAINT purchase_order_warehouse_fk_tenant;
ALTER TABLE inventory.purchase_order
    DROP CONSTRAINT purchase_order_warehouse_fk;
ALTER TABLE inventory.purchase_order
    RENAME CONSTRAINT purchase_order_warehouse_fk_tenant TO purchase_order_warehouse_fk;
ALTER TABLE inventory.purchase_order
    ADD CONSTRAINT purchase_order_supplier_fk_tenant FOREIGN KEY (tenant_id, supplier_id)
        REFERENCES inventory.supplier (tenant_id, id) NOT VALID;
ALTER TABLE inventory.purchase_order
    VALIDATE CONSTRAINT purchase_order_supplier_fk_tenant;
ALTER TABLE inventory.purchase_order
    DROP CONSTRAINT purchase_order_supplier_fk;
ALTER TABLE inventory.purchase_order
    RENAME CONSTRAINT purchase_order_supplier_fk_tenant TO purchase_order_supplier_fk;
ALTER TABLE inventory.purchase_order_line
    ADD CONSTRAINT purchase_order_line_order_fk_tenant FOREIGN KEY (tenant_id, purchase_order_id)
        REFERENCES inventory.purchase_order (tenant_id, id) NOT VALID;
ALTER TABLE inventory.purchase_order_line
    VALIDATE CONSTRAINT purchase_order_line_order_fk_tenant;
ALTER TABLE inventory.purchase_order_line
    DROP CONSTRAINT purchase_order_line_order_fk;
ALTER TABLE inventory.purchase_order_line
    RENAME CONSTRAINT purchase_order_line_order_fk_tenant TO purchase_order_line_order_fk;
ALTER TABLE inventory.purchase_order_line
    ADD CONSTRAINT purchase_order_line_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.purchase_order_line
    VALIDATE CONSTRAINT purchase_order_line_item_fk_tenant;
ALTER TABLE inventory.purchase_order_line
    DROP CONSTRAINT purchase_order_line_item_fk;
ALTER TABLE inventory.purchase_order_line
    RENAME CONSTRAINT purchase_order_line_item_fk_tenant TO purchase_order_line_item_fk;
ALTER TABLE inventory.purchase_order_line
    ADD CONSTRAINT purchase_order_line_pack_fk_tenant FOREIGN KEY (tenant_id, pack_id)
        REFERENCES inventory.stock_item_pack (tenant_id, id) NOT VALID;
ALTER TABLE inventory.purchase_order_line
    VALIDATE CONSTRAINT purchase_order_line_pack_fk_tenant;
ALTER TABLE inventory.purchase_order_line
    DROP CONSTRAINT purchase_order_line_pack_fk;
ALTER TABLE inventory.purchase_order_line
    RENAME CONSTRAINT purchase_order_line_pack_fk_tenant TO purchase_order_line_pack_fk;
ALTER TABLE inventory.goods_receipt
    ADD CONSTRAINT goods_receipt_warehouse_fk_tenant FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.goods_receipt
    VALIDATE CONSTRAINT goods_receipt_warehouse_fk_tenant;
ALTER TABLE inventory.goods_receipt
    DROP CONSTRAINT goods_receipt_warehouse_fk;
ALTER TABLE inventory.goods_receipt
    RENAME CONSTRAINT goods_receipt_warehouse_fk_tenant TO goods_receipt_warehouse_fk;
ALTER TABLE inventory.goods_receipt
    ADD CONSTRAINT goods_receipt_supplier_fk_tenant FOREIGN KEY (tenant_id, supplier_id)
        REFERENCES inventory.supplier (tenant_id, id) NOT VALID;
ALTER TABLE inventory.goods_receipt
    VALIDATE CONSTRAINT goods_receipt_supplier_fk_tenant;
ALTER TABLE inventory.goods_receipt
    DROP CONSTRAINT goods_receipt_supplier_fk;
ALTER TABLE inventory.goods_receipt
    RENAME CONSTRAINT goods_receipt_supplier_fk_tenant TO goods_receipt_supplier_fk;
ALTER TABLE inventory.goods_receipt
    ADD CONSTRAINT goods_receipt_order_fk_tenant FOREIGN KEY (tenant_id, purchase_order_id)
        REFERENCES inventory.purchase_order (tenant_id, id) NOT VALID;
ALTER TABLE inventory.goods_receipt
    VALIDATE CONSTRAINT goods_receipt_order_fk_tenant;
ALTER TABLE inventory.goods_receipt
    DROP CONSTRAINT goods_receipt_order_fk;
ALTER TABLE inventory.goods_receipt
    RENAME CONSTRAINT goods_receipt_order_fk_tenant TO goods_receipt_order_fk;
ALTER TABLE inventory.goods_receipt_line
    ADD CONSTRAINT goods_receipt_line_receipt_fk_tenant FOREIGN KEY (tenant_id, receipt_id)
        REFERENCES inventory.goods_receipt (tenant_id, id) NOT VALID;
ALTER TABLE inventory.goods_receipt_line
    VALIDATE CONSTRAINT goods_receipt_line_receipt_fk_tenant;
ALTER TABLE inventory.goods_receipt_line
    DROP CONSTRAINT goods_receipt_line_receipt_fk;
ALTER TABLE inventory.goods_receipt_line
    RENAME CONSTRAINT goods_receipt_line_receipt_fk_tenant TO goods_receipt_line_receipt_fk;
ALTER TABLE inventory.goods_receipt_line
    ADD CONSTRAINT goods_receipt_line_order_line_fk_tenant FOREIGN KEY (tenant_id, purchase_order_line_id)
        REFERENCES inventory.purchase_order_line (tenant_id, id) NOT VALID;
ALTER TABLE inventory.goods_receipt_line
    VALIDATE CONSTRAINT goods_receipt_line_order_line_fk_tenant;
ALTER TABLE inventory.goods_receipt_line
    DROP CONSTRAINT goods_receipt_line_order_line_fk;
ALTER TABLE inventory.goods_receipt_line
    RENAME CONSTRAINT goods_receipt_line_order_line_fk_tenant TO goods_receipt_line_order_line_fk;
ALTER TABLE inventory.goods_receipt_line
    ADD CONSTRAINT goods_receipt_line_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.goods_receipt_line
    VALIDATE CONSTRAINT goods_receipt_line_item_fk_tenant;
ALTER TABLE inventory.goods_receipt_line
    DROP CONSTRAINT goods_receipt_line_item_fk;
ALTER TABLE inventory.goods_receipt_line
    RENAME CONSTRAINT goods_receipt_line_item_fk_tenant TO goods_receipt_line_item_fk;
ALTER TABLE inventory.goods_receipt_line
    ADD CONSTRAINT goods_receipt_line_pack_fk_tenant FOREIGN KEY (tenant_id, pack_id)
        REFERENCES inventory.stock_item_pack (tenant_id, id) NOT VALID;
ALTER TABLE inventory.goods_receipt_line
    VALIDATE CONSTRAINT goods_receipt_line_pack_fk_tenant;
ALTER TABLE inventory.goods_receipt_line
    DROP CONSTRAINT goods_receipt_line_pack_fk;
ALTER TABLE inventory.goods_receipt_line
    RENAME CONSTRAINT goods_receipt_line_pack_fk_tenant TO goods_receipt_line_pack_fk;
ALTER TABLE inventory.supplier_return
    ADD CONSTRAINT supplier_return_warehouse_fk_tenant FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.supplier_return
    VALIDATE CONSTRAINT supplier_return_warehouse_fk_tenant;
ALTER TABLE inventory.supplier_return
    DROP CONSTRAINT supplier_return_warehouse_fk;
ALTER TABLE inventory.supplier_return
    RENAME CONSTRAINT supplier_return_warehouse_fk_tenant TO supplier_return_warehouse_fk;
ALTER TABLE inventory.supplier_return
    ADD CONSTRAINT supplier_return_supplier_fk_tenant FOREIGN KEY (tenant_id, supplier_id)
        REFERENCES inventory.supplier (tenant_id, id) NOT VALID;
ALTER TABLE inventory.supplier_return
    VALIDATE CONSTRAINT supplier_return_supplier_fk_tenant;
ALTER TABLE inventory.supplier_return
    DROP CONSTRAINT supplier_return_supplier_fk;
ALTER TABLE inventory.supplier_return
    RENAME CONSTRAINT supplier_return_supplier_fk_tenant TO supplier_return_supplier_fk;
ALTER TABLE inventory.supplier_return
    ADD CONSTRAINT supplier_return_receipt_fk_tenant FOREIGN KEY (tenant_id, goods_receipt_id)
        REFERENCES inventory.goods_receipt (tenant_id, id) NOT VALID;
ALTER TABLE inventory.supplier_return
    VALIDATE CONSTRAINT supplier_return_receipt_fk_tenant;
ALTER TABLE inventory.supplier_return
    DROP CONSTRAINT supplier_return_receipt_fk;
ALTER TABLE inventory.supplier_return
    RENAME CONSTRAINT supplier_return_receipt_fk_tenant TO supplier_return_receipt_fk;
ALTER TABLE inventory.supplier_return_line
    ADD CONSTRAINT supplier_return_line_return_fk_tenant FOREIGN KEY (tenant_id, return_id)
        REFERENCES inventory.supplier_return (tenant_id, id) NOT VALID;
ALTER TABLE inventory.supplier_return_line
    VALIDATE CONSTRAINT supplier_return_line_return_fk_tenant;
ALTER TABLE inventory.supplier_return_line
    DROP CONSTRAINT supplier_return_line_return_fk;
ALTER TABLE inventory.supplier_return_line
    RENAME CONSTRAINT supplier_return_line_return_fk_tenant TO supplier_return_line_return_fk;
ALTER TABLE inventory.supplier_return_line
    ADD CONSTRAINT supplier_return_line_receipt_line_fk_tenant FOREIGN KEY (tenant_id, receipt_line_id)
        REFERENCES inventory.goods_receipt_line (tenant_id, id) NOT VALID;
ALTER TABLE inventory.supplier_return_line
    VALIDATE CONSTRAINT supplier_return_line_receipt_line_fk_tenant;
ALTER TABLE inventory.supplier_return_line
    DROP CONSTRAINT supplier_return_line_receipt_line_fk;
ALTER TABLE inventory.supplier_return_line
    RENAME CONSTRAINT supplier_return_line_receipt_line_fk_tenant TO supplier_return_line_receipt_line_fk;
ALTER TABLE inventory.supplier_return_line
    ADD CONSTRAINT supplier_return_line_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.supplier_return_line
    VALIDATE CONSTRAINT supplier_return_line_item_fk_tenant;
ALTER TABLE inventory.supplier_return_line
    DROP CONSTRAINT supplier_return_line_item_fk;
ALTER TABLE inventory.supplier_return_line
    RENAME CONSTRAINT supplier_return_line_item_fk_tenant TO supplier_return_line_item_fk;
ALTER TABLE inventory.receipt_cost_correction
    ADD CONSTRAINT receipt_cost_correction_receipt_fk_tenant FOREIGN KEY (tenant_id, receipt_id)
        REFERENCES inventory.goods_receipt (tenant_id, id) NOT VALID;
ALTER TABLE inventory.receipt_cost_correction
    VALIDATE CONSTRAINT receipt_cost_correction_receipt_fk_tenant;
ALTER TABLE inventory.receipt_cost_correction
    DROP CONSTRAINT receipt_cost_correction_receipt_fk;
ALTER TABLE inventory.receipt_cost_correction
    RENAME CONSTRAINT receipt_cost_correction_receipt_fk_tenant TO receipt_cost_correction_receipt_fk;
ALTER TABLE inventory.receipt_cost_correction
    ADD CONSTRAINT receipt_cost_correction_line_fk_tenant FOREIGN KEY (tenant_id, receipt_line_id)
        REFERENCES inventory.goods_receipt_line (tenant_id, id) NOT VALID;
ALTER TABLE inventory.receipt_cost_correction
    VALIDATE CONSTRAINT receipt_cost_correction_line_fk_tenant;
ALTER TABLE inventory.receipt_cost_correction
    DROP CONSTRAINT receipt_cost_correction_line_fk;
ALTER TABLE inventory.receipt_cost_correction
    RENAME CONSTRAINT receipt_cost_correction_line_fk_tenant TO receipt_cost_correction_line_fk;
ALTER TABLE inventory.cost_variance
    ADD CONSTRAINT cost_variance_correction_fk_tenant FOREIGN KEY (tenant_id, correction_id)
        REFERENCES inventory.receipt_cost_correction (tenant_id, id) NOT VALID;
ALTER TABLE inventory.cost_variance
    VALIDATE CONSTRAINT cost_variance_correction_fk_tenant;
ALTER TABLE inventory.cost_variance
    DROP CONSTRAINT cost_variance_correction_fk;
ALTER TABLE inventory.cost_variance
    RENAME CONSTRAINT cost_variance_correction_fk_tenant TO cost_variance_correction_fk;
ALTER TABLE inventory.cost_variance
    ADD CONSTRAINT cost_variance_return_line_fk_tenant FOREIGN KEY (tenant_id, return_line_id)
        REFERENCES inventory.supplier_return_line (tenant_id, id) NOT VALID;
ALTER TABLE inventory.cost_variance
    VALIDATE CONSTRAINT cost_variance_return_line_fk_tenant;
ALTER TABLE inventory.cost_variance
    DROP CONSTRAINT cost_variance_return_line_fk;
ALTER TABLE inventory.cost_variance
    RENAME CONSTRAINT cost_variance_return_line_fk_tenant TO cost_variance_return_line_fk;
ALTER TABLE inventory.cost_variance
    ADD CONSTRAINT cost_variance_receipt_line_fk_tenant FOREIGN KEY (tenant_id, receipt_line_id)
        REFERENCES inventory.goods_receipt_line (tenant_id, id) NOT VALID;
ALTER TABLE inventory.cost_variance
    VALIDATE CONSTRAINT cost_variance_receipt_line_fk_tenant;
ALTER TABLE inventory.cost_variance
    DROP CONSTRAINT cost_variance_receipt_line_fk;
ALTER TABLE inventory.cost_variance
    RENAME CONSTRAINT cost_variance_receipt_line_fk_tenant TO cost_variance_receipt_line_fk;
ALTER TABLE inventory.stock_count
    ADD CONSTRAINT stock_count_warehouse_fk_tenant FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_count
    VALIDATE CONSTRAINT stock_count_warehouse_fk_tenant;
ALTER TABLE inventory.stock_count
    DROP CONSTRAINT stock_count_warehouse_fk;
ALTER TABLE inventory.stock_count
    RENAME CONSTRAINT stock_count_warehouse_fk_tenant TO stock_count_warehouse_fk;
ALTER TABLE inventory.stock_count_line
    ADD CONSTRAINT stock_count_line_count_fk_tenant FOREIGN KEY (tenant_id, count_id)
        REFERENCES inventory.stock_count (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_count_line
    VALIDATE CONSTRAINT stock_count_line_count_fk_tenant;
ALTER TABLE inventory.stock_count_line
    DROP CONSTRAINT stock_count_line_count_fk;
ALTER TABLE inventory.stock_count_line
    RENAME CONSTRAINT stock_count_line_count_fk_tenant TO stock_count_line_count_fk;
ALTER TABLE inventory.stock_count_line
    ADD CONSTRAINT stock_count_line_item_fk_tenant FOREIGN KEY (tenant_id, stock_item_id)
        REFERENCES inventory.stock_item (tenant_id, id) NOT VALID;
ALTER TABLE inventory.stock_count_line
    VALIDATE CONSTRAINT stock_count_line_item_fk_tenant;
ALTER TABLE inventory.stock_count_line
    DROP CONSTRAINT stock_count_line_item_fk;
ALTER TABLE inventory.stock_count_line
    RENAME CONSTRAINT stock_count_line_item_fk_tenant TO stock_count_line_item_fk;
ALTER TABLE inventory.warehouse
    DROP CONSTRAINT warehouse_id_store_uk;
ALTER TABLE inventory.warehouse
    RENAME CONSTRAINT warehouse_tenant_id_store_uk TO warehouse_id_store_uk;

-- (6) The policies through the helpers, then the registries (out of UNSCOPED).
-- Protected by V13081 (store policies): the restrictive tenant policy joins them.
SELECT shared.rls_protect_tenant('inventory.consumption_route');
SELECT shared.rls_protect_tenant('inventory.inventory_activation');
SELECT shared.rls_protect_tenant('inventory.stock_movement');
SELECT shared.rls_protect_tenant('inventory.stock_balance');
SELECT shared.rls_protect_tenant('inventory.balance_check');
SELECT shared.rls_protect_tenant('inventory.consumed_line');
SELECT shared.rls_protect_tenant('inventory.line_void');
SELECT shared.rls_protect_tenant('inventory.waste_record');
SELECT shared.rls_protect_tenant('inventory.consumption_exception');
SELECT shared.rls_protect_tenant('inventory.cost_variance');
SELECT shared.rls_protect_tenant('inventory.preparation');
SELECT shared.rls_protect_tenant('inventory.purchase_order');
SELECT shared.rls_protect_tenant('inventory.goods_receipt');
SELECT shared.rls_protect_tenant('inventory.supplier_return');
SELECT shared.rls_protect_tenant('inventory.stock_count');
SELECT shared.rls_protect_tenant('inventory.warehouse');
SELECT shared.rls_protect_tenant('inventory.stock_transfer');
SELECT shared.rls_protect_tenant('inventory.stock_transfer_line');
SELECT shared.rls_protect_tenant('inventory.stock_level');
SELECT shared.rls_protect_tenant('inventory.purchase_order_line');
SELECT shared.rls_protect_tenant('inventory.goods_receipt_line');
SELECT shared.rls_protect_tenant('inventory.receipt_cost_correction');
SELECT shared.rls_protect_tenant('inventory.supplier_return_line');
SELECT shared.rls_protect_tenant('inventory.stock_count_line');

-- Formerly UNSCOPED (company-wide, D167): TENANT class, no store predicate.
DELETE FROM shared.rls_unscoped_table
WHERE table_name IN ('inventory.stock_item'::regclass, 'inventory.stock_item_pack'::regclass, 'inventory.supplier'::regclass, 'inventory.supplier_item'::regclass, 'inventory.recipe'::regclass, 'inventory.recipe_version'::regclass, 'inventory.recipe_ingredient'::regclass, 'inventory.consumption_policy_version'::regclass, 'inventory.consumption_policy_rule'::regclass);
SELECT shared.rls_protect_tenant_table('inventory.stock_item');
SELECT shared.rls_protect_tenant_table('inventory.stock_item_pack');
SELECT shared.rls_protect_tenant_table('inventory.supplier');
SELECT shared.rls_protect_tenant_table('inventory.supplier_item');
SELECT shared.rls_protect_tenant_table('inventory.recipe');
SELECT shared.rls_protect_tenant_table('inventory.recipe_version');
SELECT shared.rls_protect_tenant_table('inventory.recipe_ingredient');
SELECT shared.rls_protect_tenant_table('inventory.consumption_policy_version');
SELECT shared.rls_protect_tenant_table('inventory.consumption_policy_rule');

-- The seeded code list of units: identical for every tenant, never written by the application (D114).
SELECT shared.rls_register_platform('inventory.stock_unit',
                                    'Stock units: a seeded code list, identical for every tenant, written by migrations only (D114)');

-- The D42 probe of a transfer (D168), now tenant-aware (D179): another tenant's transfer reads as absent (no row ->
-- 404, existence never revealed), another store of the same tenant still answers its two stores (-> 403). The bound
-- tenant is read first, so an unbound call raises RS003 even when no transfer matches. EXECUTE is the application
-- role's only (security rule 9); like shared.store_of it pins search_path and is covered by a test.
CREATE OR REPLACE FUNCTION inventory.stock_transfer_stores(p_id uuid)
    RETURNS TABLE
            (
                from_store_id uuid,
                to_store_id   uuid
            )
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
DECLARE
    v_tenant uuid := shared.rls_tenant_id();
BEGIN
    RETURN QUERY
        SELECT t.from_store_id, t.to_store_id
        FROM inventory.stock_transfer t
        WHERE t.id = p_id
          AND t.tenant_id = v_tenant;
END;
$$;
REVOKE EXECUTE ON FUNCTION inventory.stock_transfer_stores(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION inventory.stock_transfer_stores(uuid) TO restaurantpro_app;
