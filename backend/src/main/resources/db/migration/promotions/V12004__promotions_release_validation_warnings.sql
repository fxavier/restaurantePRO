-- R5 (D147 amended 2026-11-13, S58 review): the warnings of the last validation, refreshed at publication, so the
-- publish screen shows them (e.g. PROMOTION_SCOPE_ITEM_EXCLUDED). A JSON array; NULL before the first validation.
-- Written while the release is DRAFT or VALIDATED, at the latest in the publishing update itself (OLD.snapshot is
-- NULL then), so the immutability trigger of V12003 needs no change.
ALTER TABLE promotions.promotion_release
    ADD COLUMN validation_warnings JSONB;
