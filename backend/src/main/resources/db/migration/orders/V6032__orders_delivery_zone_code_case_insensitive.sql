-- FE-F35: a delivery zone code is unique in its store whatever its case, so "abc" and "ABC" can no longer coexist.
-- Unlike FE-F21, the application keeps the code as entered (trimmed, case unchanged); only the comparison ignores
-- case. The key is rebuilt on the same columns with upper(code), leading with tenant_id (D179/D180 as amended), and
-- replaces the case-sensitive constraint under its name: UniqueConstraints maps the violation by name to
-- DELIVERY_ZONE_CODE_TAKEN (no pre-check in DeliveryZoneService: the key decides). No foreign key references this key
-- (an expression index cannot be a foreign-key target). Delivery orders keep their zone snapshot unchanged.
-- No UPDATE: existing rows keep their case (non-destructive). Pre-pilot assumption (P70): no store already holds two
-- zone codes that differ only in case; if one does, the index build fails with a unique violation naming the pair,
-- to be resolved by hand before re-running.
CREATE UNIQUE INDEX delivery_zone_store_code_ci_uk ON orders.delivery_zone (tenant_id, store_id, upper(code));
ALTER TABLE orders.delivery_zone DROP CONSTRAINT delivery_zone_store_code_uk;
ALTER INDEX orders.delivery_zone_store_code_ci_uk RENAME TO delivery_zone_store_code_uk;
