-- R5 (D147 amended 2026-11-13, S58 review): the warnings of the last validation, refreshed at publication, so the
-- publish screen shows them (e.g. PROMOTION_SCOPE_ITEM_EXCLUDED). A JSON array; NULL before the first validation.
-- Frozen with the rest of a published release: the trigger function of V12003 is replaced with validation_warnings
-- added to its frozen columns. The publishing update itself still writes it, because OLD.snapshot is NULL then.
ALTER TABLE promotions.promotion_release
    ADD COLUMN validation_warnings JSONB;

CREATE OR REPLACE FUNCTION promotions.promotion_release_keep_frozen() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'promotion releases cannot be truncated'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.snapshot IS NOT NULL THEN
            RAISE EXCEPTION 'a published promotion release is immutable (release %)', OLD.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.snapshot IS NOT NULL AND (
        NEW.snapshot IS DISTINCT FROM OLD.snapshot
            OR NEW.version_no IS DISTINCT FROM OLD.version_no
            OR NEW.effective_at IS DISTINCT FROM OLD.effective_at
            OR NEW.published_by_account_id IS DISTINCT FROM OLD.published_by_account_id
            OR NEW.store_id IS DISTINCT FROM OLD.store_id
            OR NEW.created_by_account_id IS DISTINCT FROM OLD.created_by_account_id
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.validated_at IS DISTINCT FROM OLD.validated_at
            OR NEW.validation_warnings IS DISTINCT FROM OLD.validation_warnings
            OR (OLD.status = 'RETIRED' AND NEW.retired_at IS DISTINCT FROM OLD.retired_at)
            OR (OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED')
            OR (OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED', 'RETIRED'))) THEN
        RAISE EXCEPTION 'a published promotion release is immutable (release %)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
