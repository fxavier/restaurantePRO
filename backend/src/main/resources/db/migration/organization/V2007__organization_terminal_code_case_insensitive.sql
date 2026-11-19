-- FE-F21: a device code (POS terminal or print bridge, one code space per store) is unique whatever its case, so
-- "cx2" and "CX2" can no longer coexist in a store. The application writes codes trimmed in uppercase; this index
-- makes the database refuse a case variant from any writer. It leads with tenant_id (D179/D180 as amended), so a
-- tenant's lookup never scans another tenant's rows, and it replaces the case-sensitive key under the same name
-- (terminal_store_code_uk: UniqueConstraints maps the violation by name to TERMINAL_CODE_TAKEN).
-- No UPDATE: existing rows keep their case (non-destructive, D180 recipe). Pre-pilot assumption (P70): no store
-- already holds two codes that differ only in case; if one does, the index build fails and the migration stops with
-- a unique violation naming the pair, to be resolved by hand before re-running.
CREATE UNIQUE INDEX terminal_store_code_ci_uk ON organization.terminal (tenant_id, store_id, upper(code));
ALTER TABLE organization.terminal
    DROP CONSTRAINT terminal_store_code_uk;
ALTER INDEX organization.terminal_store_code_ci_uk RENAME TO terminal_store_code_uk;
