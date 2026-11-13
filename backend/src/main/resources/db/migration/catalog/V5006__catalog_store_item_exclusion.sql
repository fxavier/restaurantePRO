-- R5 per-store availability (D146, ADR 0036): every active item is in every store unless excluded there. An excluded
-- item is left out of that store's next release and needs no price in its price list. No row = R4 behaviour, so no
-- data migration (D148). Store ids reference organization.store by id only (D6).
CREATE TABLE catalog.store_item_exclusion
(
    store_id               UUID                     NOT NULL,
    item_id                UUID                     NOT NULL,
    reason                 VARCHAR(200),
    excluded_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    excluded_by_account_id UUID                     NOT NULL,
    CONSTRAINT store_item_exclusion_pk PRIMARY KEY (store_id, item_id),
    CONSTRAINT store_item_exclusion_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id)
);
CREATE INDEX store_item_exclusion_item_ix ON catalog.store_item_exclusion (item_id);

-- The warnings of the last validation (D147), refreshed by the publishing update, so the publish screen shows them.
-- NULL before the first validation. Frozen with the rest of a PUBLISHED or RETIRED release: the trigger function of
-- V5002 is replaced with validation_warnings added to its frozen columns (the publishing update itself still writes
-- it, because OLD.status is VALIDATED then).
ALTER TABLE catalog.configuration_release
    ADD COLUMN validation_warnings JSONB;

CREATE OR REPLACE FUNCTION catalog.configuration_release_keep_frozen() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'catalog releases cannot be truncated'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('PUBLISHED', 'RETIRED') THEN
            RAISE EXCEPTION 'a published catalog release is immutable (release %)', OLD.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status IN ('PUBLISHED', 'RETIRED') AND (
        NEW.snapshot IS DISTINCT FROM OLD.snapshot
            OR NEW.catalog_version IS DISTINCT FROM OLD.catalog_version
            OR NEW.effective_at IS DISTINCT FROM OLD.effective_at
            OR NEW.published_by IS DISTINCT FROM OLD.published_by
            OR NEW.store_id IS DISTINCT FROM OLD.store_id
            OR NEW.created_by IS DISTINCT FROM OLD.created_by
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.validated_at IS DISTINCT FROM OLD.validated_at
            OR NEW.validation_warnings IS DISTINCT FROM OLD.validation_warnings
            OR (OLD.status = 'RETIRED' AND NEW.retired_at IS DISTINCT FROM OLD.retired_at)
            OR (OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED')
            OR (OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED', 'RETIRED'))) THEN
        RAISE EXCEPTION 'a published catalog release is immutable (release %)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
