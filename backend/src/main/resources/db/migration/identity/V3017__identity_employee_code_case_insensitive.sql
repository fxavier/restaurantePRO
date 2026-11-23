-- FE-F35: an employee code is unique in its tenant whatever its case, so "abc" and "ABC" can no longer coexist.
-- Unlike FE-F21, the application keeps the code as entered (trimmed, case unchanged); only the comparison ignores
-- case (the service pre-check is IgnoreCase). The key is rebuilt on the same columns with upper(code), leading with
-- tenant_id (D179/D180 as amended), and replaces the case-sensitive constraint under its name: UniqueConstraints maps
-- the violation by name to EMPLOYEE_CODE_TAKEN. No foreign key references this key (an expression index cannot be a
-- foreign-key target). The POS PIN check still looks the employee up by the exact code.
-- No UPDATE: existing rows keep their case (non-destructive). Pre-pilot assumption (P70): no tenant already holds two
-- employee codes that differ only in case; if one does, the index build fails with a unique violation naming the
-- pair, to be resolved by hand before re-running.
CREATE UNIQUE INDEX employee_code_ci_uk ON identity.employee (tenant_id, upper(code));
ALTER TABLE identity.employee DROP CONSTRAINT employee_code_uk;
ALTER INDEX identity.employee_code_ci_uk RENAME TO employee_code_uk;
