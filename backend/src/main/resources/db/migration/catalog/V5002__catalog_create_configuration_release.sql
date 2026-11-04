-- Catalog releases (D31, spec §8.3): DRAFT -> VALIDATED -> PUBLISHED -> RETIRED. Publishing freezes the whole
-- per-store catalog as jsonb. A published snapshot is a confirmed fact: never edited, never deleted; its
-- audit facts (author, creation, validation, retirement instants) are frozen with it.
CREATE TABLE catalog.configuration_release
(
    id              UUID                     NOT NULL,
    store_id        UUID                     NOT NULL,
    status          VARCHAR(16)              NOT NULL,
    catalog_version BIGINT,
    snapshot        JSONB,
    effective_at    TIMESTAMP WITH TIME ZONE,
    published_by    UUID,
    created_by      UUID                     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    validated_at    TIMESTAMP WITH TIME ZONE,
    retired_at      TIMESTAMP WITH TIME ZONE,
    lock_version    BIGINT                   NOT NULL,
    CONSTRAINT configuration_release_pk PRIMARY KEY (id),
    CONSTRAINT configuration_release_status_ck CHECK (status IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT configuration_release_store_version_uk UNIQUE (store_id, catalog_version),
    CONSTRAINT configuration_release_version_positive_ck CHECK (catalog_version > 0),
    CONSTRAINT configuration_release_frozen_ck CHECK (
        (status IN ('DRAFT', 'VALIDATED')
            AND catalog_version IS NULL AND snapshot IS NULL AND effective_at IS NULL AND published_by IS NULL)
            OR (status IN ('PUBLISHED', 'RETIRED')
            AND catalog_version IS NOT NULL AND snapshot IS NOT NULL AND effective_at IS NOT NULL
            AND published_by IS NOT NULL)),
    CONSTRAINT configuration_release_retired_ck CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL))
);

-- At most one PUBLISHED release per store; a concurrent second publication fails here (409).
CREATE UNIQUE INDEX configuration_release_one_published_per_store_ux
    ON catalog.configuration_release (store_id) WHERE status = 'PUBLISHED';

CREATE FUNCTION catalog.configuration_release_keep_frozen() RETURNS trigger
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
            OR (OLD.status = 'RETIRED' AND NEW.retired_at IS DISTINCT FROM OLD.retired_at)
            OR (OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED')
            OR (OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED', 'RETIRED'))) THEN
        RAISE EXCEPTION 'a published catalog release is immutable (release %)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER configuration_release_keep_frozen_trg
    BEFORE UPDATE OR DELETE ON catalog.configuration_release
    FOR EACH ROW EXECUTE FUNCTION catalog.configuration_release_keep_frozen();

CREATE TRIGGER configuration_release_no_truncate_trg
    BEFORE TRUNCATE ON catalog.configuration_release
    FOR EACH STATEMENT EXECUTE FUNCTION catalog.configuration_release_keep_frozen();
