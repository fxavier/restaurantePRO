-- FE-F35: a room area code and a restaurant table code are unique in their store whatever their case, so "abc" and
-- "ABC" can no longer coexist. Unlike FE-F21, the application keeps the code as entered (trimmed, case unchanged);
-- only the comparison ignores case (the service pre-checks are IgnoreCase). Each key is rebuilt on the same columns
-- with upper(code), leading with tenant_id (D179/D180 as amended), and replaces the case-sensitive constraint under
-- its name: UniqueConstraints maps the violation by name to DINING_CODE_TAKEN. No foreign key references either key
-- (an expression index cannot be a foreign-key target).
-- No UPDATE: existing rows keep their case (non-destructive). Pre-pilot assumption (P70): no store already holds two
-- codes that differ only in case; if one does, the index build fails with a unique violation naming the pair, to be
-- resolved by hand before re-running.
CREATE UNIQUE INDEX room_area_store_code_ci_uk ON dining.room_area (tenant_id, store_id, upper(code));
ALTER TABLE dining.room_area DROP CONSTRAINT room_area_store_code_uk;
ALTER INDEX dining.room_area_store_code_ci_uk RENAME TO room_area_store_code_uk;

CREATE UNIQUE INDEX restaurant_table_store_code_ci_uk ON dining.restaurant_table (tenant_id, store_id, upper(code));
ALTER TABLE dining.restaurant_table DROP CONSTRAINT restaurant_table_store_code_uk;
ALTER INDEX dining.restaurant_table_store_code_ci_uk RENAME TO restaurant_table_store_code_uk;
