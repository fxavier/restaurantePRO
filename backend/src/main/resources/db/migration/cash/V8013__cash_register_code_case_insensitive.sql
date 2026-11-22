-- FE-F35: a cash register code is unique in its store whatever its case, so "abc" and "ABC" can no longer coexist.
-- Unlike FE-F21, the application keeps the code as entered (trimmed, case unchanged); only the comparison ignores
-- case. The key is rebuilt on the same columns with upper(code), leading with tenant_id (D179/D180 as amended), and
-- replaces the case-sensitive constraint under its name: UniqueConstraints maps the violation by name to
-- REGISTER_CODE_TAKEN (no pre-check in CashRegisterService: the key decides). No foreign key references this key (an
-- expression index cannot be a foreign-key target).
-- No UPDATE: existing rows keep their case (non-destructive). Pre-pilot assumption (P70): no store already holds two
-- register codes that differ only in case; if one does, the index build fails with a unique violation naming the
-- pair, to be resolved by hand before re-running.
CREATE UNIQUE INDEX cash_register_store_code_ci_uk ON cash.cash_register (tenant_id, store_id, upper(code));
ALTER TABLE cash.cash_register DROP CONSTRAINT cash_register_store_code_uk;
ALTER INDEX cash.cash_register_store_code_ci_uk RENAME TO cash_register_store_code_uk;
