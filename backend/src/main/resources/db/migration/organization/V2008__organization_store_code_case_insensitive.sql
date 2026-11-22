-- FE-F35: a store code is unique in its tenant whatever its case, so "abc" and "ABC" can no longer coexist. Unlike
-- FE-F21, the application keeps the code as entered (trimmed, case unchanged); only the comparison ignores case (the
-- service pre-check is IgnoreCase). The key is rebuilt on the same columns with upper(code), leading with tenant_id
-- (D179/D180 as amended), and replaces the case-sensitive constraint under its name: UniqueConstraints maps the
-- violation by name to STORE_CODE_TAKEN. No foreign key references this key (an expression index cannot be a
-- foreign-key target).
-- No UPDATE: existing rows keep their case (non-destructive). Pre-pilot assumption (P70): no tenant already holds two
-- store codes that differ only in case; if one does, the index build fails with a unique violation naming the pair,
-- to be resolved by hand before re-running.
CREATE UNIQUE INDEX store_code_ci_uk ON organization.store (tenant_id, upper(code));
ALTER TABLE organization.store DROP CONSTRAINT store_code_uk;
ALTER INDEX organization.store_code_ci_uk RENAME TO store_code_uk;
