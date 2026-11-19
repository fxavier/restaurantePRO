-- FE-F35: a promotion code (per tenant) and a loyalty reward code (per program version) are unique whatever their
-- case, so "abc" and "ABC" can no longer coexist in the same scope. Unlike FE-F21, the application keeps the code as
-- entered (trimmed, case unchanged); only the comparison ignores case. Each key is rebuilt on the same columns with
-- upper(code), leading with tenant_id (D179/D180 as amended), and replaces the case-sensitive constraint under its
-- name: UniqueConstraints maps the violation by name to PROMOTION_CODE_TAKEN / LOYALTY_REWARD_CODE_TAKEN. No foreign
-- key references either key (an expression index cannot be a foreign-key target). Published program versions keep
-- their reward rows unchanged; the key only refuses new case variants within a version.
-- No UPDATE: existing rows keep their case (non-destructive). Pre-pilot assumption (P70): no scope already holds two
-- codes that differ only in case; if one does, the index build fails with a unique violation naming the pair, to be
-- resolved by hand before re-running.
CREATE UNIQUE INDEX promotion_code_ci_uk ON promotions.promotion (tenant_id, upper(code));
ALTER TABLE promotions.promotion DROP CONSTRAINT promotion_code_uk;
ALTER INDEX promotions.promotion_code_ci_uk RENAME TO promotion_code_uk;

CREATE UNIQUE INDEX loyalty_reward_code_ci_uk ON promotions.loyalty_reward (tenant_id, version_id, upper(code));
ALTER TABLE promotions.loyalty_reward DROP CONSTRAINT loyalty_reward_code_uk;
ALTER INDEX promotions.loyalty_reward_code_ci_uk RENAME TO loyalty_reward_code_uk;
