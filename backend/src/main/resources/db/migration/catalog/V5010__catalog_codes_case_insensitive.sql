-- FE-F35: a working-copy code (family, unit, tax category, item, variant of an item, menu group of an item, macro,
-- quick page of a store or company-wide) is unique whatever its case, so "abc" and "ABC" can no longer coexist in
-- the same scope. Unlike FE-F21, the application keeps the code as entered (trimmed, case unchanged); only the
-- comparison ignores case. Each key is rebuilt on the same columns with upper(code), leading with tenant_id
-- (D179/D180 as amended), and replaces the case-sensitive constraint under its name: UniqueConstraints maps the
-- violation by name to CATALOG_CODE_TAKEN. No foreign key references any of these keys (an expression index cannot
-- be a foreign-key target). Barcodes are not codes and keep their exact-match key (barcode_code_uk). Published
-- releases are jsonb snapshots in configuration_release: untouched.
-- No UPDATE: existing rows keep their case (non-destructive). Pre-pilot assumption (P70): no scope already holds two
-- codes that differ only in case; if one does, the index build fails with a unique violation naming the pair, to be
-- resolved by hand before re-running.
CREATE UNIQUE INDEX family_code_ci_uk ON catalog.family (tenant_id, upper(code));
ALTER TABLE catalog.family DROP CONSTRAINT family_code_uk;
ALTER INDEX catalog.family_code_ci_uk RENAME TO family_code_uk;

CREATE UNIQUE INDEX unit_code_ci_uk ON catalog.unit (tenant_id, upper(code));
ALTER TABLE catalog.unit DROP CONSTRAINT unit_code_uk;
ALTER INDEX catalog.unit_code_ci_uk RENAME TO unit_code_uk;

CREATE UNIQUE INDEX tax_category_code_ci_uk ON catalog.tax_category (tenant_id, upper(code));
ALTER TABLE catalog.tax_category DROP CONSTRAINT tax_category_code_uk;
ALTER INDEX catalog.tax_category_code_ci_uk RENAME TO tax_category_code_uk;

CREATE UNIQUE INDEX item_code_ci_uk ON catalog.item (tenant_id, upper(code));
ALTER TABLE catalog.item DROP CONSTRAINT item_code_uk;
ALTER INDEX catalog.item_code_ci_uk RENAME TO item_code_uk;

CREATE UNIQUE INDEX item_variant_item_code_ci_uk ON catalog.item_variant (tenant_id, item_id, upper(code));
ALTER TABLE catalog.item_variant DROP CONSTRAINT item_variant_item_code_uk;
ALTER INDEX catalog.item_variant_item_code_ci_uk RENAME TO item_variant_item_code_uk;

CREATE UNIQUE INDEX menu_group_item_code_ci_uk ON catalog.menu_group (tenant_id, item_id, upper(code));
ALTER TABLE catalog.menu_group DROP CONSTRAINT menu_group_item_code_uk;
ALTER INDEX catalog.menu_group_item_code_ci_uk RENAME TO menu_group_item_code_uk;

CREATE UNIQUE INDEX macro_code_ci_uk ON catalog.macro (tenant_id, upper(code));
ALTER TABLE catalog.macro DROP CONSTRAINT macro_code_uk;
ALTER INDEX catalog.macro_code_ci_uk RENAME TO macro_code_uk;

-- A company-wide page has no store: NULLS NOT DISTINCT keeps one code space for them, as before.
CREATE UNIQUE INDEX quick_page_store_code_ci_uk ON catalog.quick_page (tenant_id, store_id, upper(code))
    NULLS NOT DISTINCT;
ALTER TABLE catalog.quick_page DROP CONSTRAINT quick_page_store_code_uk;
ALTER INDEX catalog.quick_page_store_code_ci_uk RENAME TO quick_page_store_code_uk;
