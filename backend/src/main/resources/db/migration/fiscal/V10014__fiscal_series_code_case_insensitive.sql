-- FE-F35: a fiscal series code is unique in its store and document type whatever its case, so "ft1" and "FT1" can
-- no longer coexist. Unlike FE-F21, the application keeps the code as entered (trimmed, case unchanged); only the
-- comparison ignores case (the service pre-check is IgnoreCase). The key is rebuilt on the same columns with
-- upper(code), leading with tenant_id (D179/D180 as amended), and replaces the case-sensitive constraint under its
-- name: SeriesService maps the violation by name to SERIES_CODE_TAKEN. No foreign key references this key (an
-- expression index cannot be a foreign-key target). fiscal_series_active_uk (one active series per store and type)
-- does not involve the code and is unchanged. Issued documents keep their series_code snapshot unchanged.
-- No UPDATE: existing rows keep their case (non-destructive). Pre-pilot assumption (P70): no store already holds two
-- series codes that differ only in case; if one does, the index build fails with a unique violation naming the
-- pair, to be resolved by hand before re-running.
CREATE UNIQUE INDEX fiscal_series_code_ci_uk ON fiscal.fiscal_series (tenant_id, store_id, document_type, upper(code));
ALTER TABLE fiscal.fiscal_series DROP CONSTRAINT fiscal_series_code_uk;
ALTER INDEX fiscal.fiscal_series_code_ci_uk RENAME TO fiscal_series_code_uk;
