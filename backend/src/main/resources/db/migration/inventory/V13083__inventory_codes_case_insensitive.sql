-- FE-F35: a stock item code and a supplier code (per tenant), a pack code (per stock item) and a warehouse code (per
-- store) are unique whatever their case, so "abc" and "ABC" can no longer coexist in the same scope. Unlike FE-F21,
-- the application keeps the code as entered (trimmed, case unchanged); only the comparison ignores case. Each key is
-- rebuilt on the same columns with upper(code), leading with tenant_id (D179/D180 as amended), and replaces the
-- case-sensitive constraint under its name: UniqueConstraints maps the violation by name to STOCK_ITEM_CODE_TAKEN /
-- PACK_CODE_TAKEN / WAREHOUSE_CODE_TAKEN / SUPPLIER_CODE_TAKEN (no pre-check in these services: the key decides). No
-- foreign key references these keys (an expression index cannot be a foreign-key target), and no ON CONFLICT targets
-- them. Stock units (stock_unit_pk) are reference data and stay exact.
-- No UPDATE: existing rows keep their case (non-destructive). Pre-pilot assumption (P70): no scope already holds two
-- codes that differ only in case; if one does, the index build fails with a unique violation naming the pair, to be
-- resolved by hand before re-running.
CREATE UNIQUE INDEX stock_item_code_ci_uk ON inventory.stock_item (tenant_id, upper(code));
ALTER TABLE inventory.stock_item DROP CONSTRAINT stock_item_code_uk;
ALTER INDEX inventory.stock_item_code_ci_uk RENAME TO stock_item_code_uk;

CREATE UNIQUE INDEX stock_item_pack_code_ci_uk ON inventory.stock_item_pack (tenant_id, stock_item_id, upper(code));
ALTER TABLE inventory.stock_item_pack DROP CONSTRAINT stock_item_pack_code_uk;
ALTER INDEX inventory.stock_item_pack_code_ci_uk RENAME TO stock_item_pack_code_uk;

CREATE UNIQUE INDEX warehouse_store_code_ci_uk ON inventory.warehouse (tenant_id, store_id, upper(code));
ALTER TABLE inventory.warehouse DROP CONSTRAINT warehouse_store_code_uk;
ALTER INDEX inventory.warehouse_store_code_ci_uk RENAME TO warehouse_store_code_uk;

CREATE UNIQUE INDEX supplier_code_ci_uk ON inventory.supplier (tenant_id, upper(code));
ALTER TABLE inventory.supplier DROP CONSTRAINT supplier_code_uk;
ALTER INDEX inventory.supplier_code_ci_uk RENAME TO supplier_code_uk;
