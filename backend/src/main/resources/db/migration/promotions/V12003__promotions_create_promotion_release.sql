-- Promotion releases per store (D102, the D31 machine): DRAFT -> VALIDATED -> PUBLISHED -> RETIRED. Publishing
-- freezes the store's promotions and discount limits as jsonb (PromotionSnapshot, schema 1). A published snapshot
-- is a confirmed fact: never edited, never deleted; lines confirmed under it keep pointing at it.
CREATE TABLE promotions.promotion_release
(
    id                      UUID                     NOT NULL,
    store_id                UUID                     NOT NULL,
    status                  VARCHAR(16)              NOT NULL,
    version_no              BIGINT,
    snapshot                JSONB,
    effective_at            TIMESTAMP WITH TIME ZONE,
    published_by_account_id UUID,
    created_by_account_id   UUID                     NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    validated_at            TIMESTAMP WITH TIME ZONE,
    retired_at              TIMESTAMP WITH TIME ZONE,
    version                 BIGINT                   NOT NULL,
    CONSTRAINT promotion_release_pk PRIMARY KEY (id),
    CONSTRAINT promotion_release_status_ck CHECK (status IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT promotion_release_store_version_uk UNIQUE (store_id, version_no),
    CONSTRAINT promotion_release_version_positive_ck CHECK (version_no > 0),
    CONSTRAINT promotion_release_frozen_ck CHECK (
        (status IN ('DRAFT', 'VALIDATED')
            AND version_no IS NULL AND snapshot IS NULL AND effective_at IS NULL
            AND published_by_account_id IS NULL)
            OR (status IN ('PUBLISHED', 'RETIRED')
            AND version_no IS NOT NULL AND snapshot IS NOT NULL AND effective_at IS NOT NULL
            AND published_by_account_id IS NOT NULL)),
    CONSTRAINT promotion_release_retired_ck CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL))
);

-- At most one PUBLISHED release per store; a concurrent second publication fails here (409).
CREATE UNIQUE INDEX promotion_release_one_published_per_store_ux
    ON promotions.promotion_release (store_id) WHERE status = 'PUBLISHED';

CREATE FUNCTION promotions.promotion_release_keep_frozen() RETURNS trigger
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
            OR (OLD.status = 'RETIRED' AND NEW.retired_at IS DISTINCT FROM OLD.retired_at)
            OR (OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED')
            OR (OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED', 'RETIRED'))) THEN
        RAISE EXCEPTION 'a published promotion release is immutable (release %)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER promotion_release_keep_frozen_trg
    BEFORE UPDATE OR DELETE ON promotions.promotion_release
    FOR EACH ROW EXECUTE FUNCTION promotions.promotion_release_keep_frozen();

CREATE TRIGGER promotion_release_no_truncate_trg
    BEFORE TRUNCATE ON promotions.promotion_release
    FOR EACH STATEMENT EXECUTE FUNCTION promotions.promotion_release_keep_frozen();
