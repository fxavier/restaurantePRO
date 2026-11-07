-- Recipes and their immutable versions (D115, §5 "Receitas e fichas técnicas"). A recipe is either a sale recipe (a
-- catalog item, optionally one of its variants) or a preparation recipe (a PREPARED stock item). Its versions are
-- DRAFT while edited, then ACTIVE, then SUPERSEDED when the next one is activated; consumption uses the version whose
-- [effective_from, effective_to) holds the line's confirmation instant, so a version is never edited once used.
CREATE TABLE inventory.recipe
(
    id                   UUID                     NOT NULL,
    item_id              UUID,
    variant_id           UUID,
    output_stock_item_id UUID,
    name                 VARCHAR(160)             NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    version              BIGINT                   NOT NULL,
    CONSTRAINT recipe_pk PRIMARY KEY (id),
    CONSTRAINT recipe_output_fk FOREIGN KEY (output_stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT recipe_kind_ck CHECK ((item_id IS NULL) <> (output_stock_item_id IS NULL)),
    CONSTRAINT recipe_variant_ck CHECK (variant_id IS NULL OR item_id IS NOT NULL),
    CONSTRAINT recipe_name_ck CHECK (btrim(name) <> '')
);
CREATE UNIQUE INDEX recipe_sale_uk ON inventory.recipe (item_id, coalesce(variant_id,
                                                                           '00000000-0000-0000-0000-000000000000'))
    WHERE item_id IS NOT NULL;
CREATE UNIQUE INDEX recipe_preparation_uk ON inventory.recipe (output_stock_item_id)
    WHERE output_stock_item_id IS NOT NULL;

CREATE TABLE inventory.recipe_version
(
    id                      UUID                     NOT NULL,
    recipe_id               UUID                     NOT NULL,
    version_no              INTEGER                  NOT NULL,
    status                  VARCHAR(12)              NOT NULL,
    yield_quantity          NUMERIC(19, 6)           NOT NULL,
    yield_unit              VARCHAR(10)              NOT NULL,
    effective_from          TIMESTAMP WITH TIME ZONE,
    effective_to            TIMESTAMP WITH TIME ZONE,
    notes                   TEXT,
    created_by_account_id   UUID,
    activated_by_account_id UUID,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_at            TIMESTAMP WITH TIME ZONE,
    version                 BIGINT                   NOT NULL,
    CONSTRAINT recipe_version_pk PRIMARY KEY (id),
    CONSTRAINT recipe_version_recipe_fk FOREIGN KEY (recipe_id) REFERENCES inventory.recipe (id),
    CONSTRAINT recipe_version_unit_fk FOREIGN KEY (yield_unit) REFERENCES inventory.stock_unit (code),
    CONSTRAINT recipe_version_no_uk UNIQUE (recipe_id, version_no),
    CONSTRAINT recipe_version_status_ck CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED')),
    CONSTRAINT recipe_version_yield_ck CHECK (yield_quantity > 0),
    CONSTRAINT recipe_version_effective_ck CHECK (
        (status = 'DRAFT' AND effective_from IS NULL AND effective_to IS NULL)
            OR (status = 'ACTIVE' AND effective_from IS NOT NULL AND effective_to IS NULL)
            OR (status = 'SUPERSEDED' AND effective_from IS NOT NULL AND effective_to > effective_from))
);
CREATE UNIQUE INDEX recipe_version_one_active_uk ON inventory.recipe_version (recipe_id) WHERE status = 'ACTIVE';
CREATE INDEX recipe_version_effective_ix ON inventory.recipe_version (recipe_id, effective_from)
    WHERE status <> 'DRAFT';

CREATE TABLE inventory.recipe_ingredient
(
    id                UUID           NOT NULL,
    recipe_version_id UUID           NOT NULL,
    stock_item_id     UUID           NOT NULL,
    quantity          NUMERIC(19, 6) NOT NULL,
    unit              VARCHAR(10)    NOT NULL,
    kind              VARCHAR(10)    NOT NULL,
    modes             TEXT[],
    position          INTEGER        NOT NULL,
    CONSTRAINT recipe_ingredient_pk PRIMARY KEY (id),
    CONSTRAINT recipe_ingredient_version_fk FOREIGN KEY (recipe_version_id) REFERENCES inventory.recipe_version (id),
    CONSTRAINT recipe_ingredient_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT recipe_ingredient_unit_fk FOREIGN KEY (unit) REFERENCES inventory.stock_unit (code),
    CONSTRAINT recipe_ingredient_quantity_ck CHECK (quantity > 0),
    CONSTRAINT recipe_ingredient_kind_ck CHECK (kind IN ('INGREDIENT', 'PACKAGING')),
    CONSTRAINT recipe_ingredient_modes_ck CHECK (modes IS NULL OR cardinality(modes) > 0)
);
CREATE INDEX recipe_ingredient_version_ix ON inventory.recipe_ingredient (recipe_version_id, position);

-- A used version is a fact: its yield, its effective start and its ingredients never change. Only the move from
-- ACTIVE to SUPERSEDED (closing effective_to) is allowed, and the effective periods of a recipe never overlap (the
-- application checks it under the recipe's row lock at activation; this is the database's guard).
CREATE FUNCTION inventory.guard_recipe_version() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'recipe version % is % and cannot be deleted', OLD.id, OLD.status
                USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status <> 'DRAFT' AND (
        NEW.yield_quantity IS DISTINCT FROM OLD.yield_quantity
            OR NEW.yield_unit IS DISTINCT FROM OLD.yield_unit
            OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
            OR NEW.recipe_id IS DISTINCT FROM OLD.recipe_id
            OR NEW.version_no IS DISTINCT FROM OLD.version_no
            OR NOT (NEW.status = OLD.status OR (OLD.status = 'ACTIVE' AND NEW.status = 'SUPERSEDED'))
            OR (OLD.status = 'SUPERSEDED' AND NEW.effective_to IS DISTINCT FROM OLD.effective_to)) THEN
        RAISE EXCEPTION 'recipe version % is % and immutable', OLD.id, OLD.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status <> 'DRAFT' AND EXISTS (
        SELECT 1 FROM inventory.recipe_version o
        WHERE o.recipe_id = NEW.recipe_id AND o.id <> NEW.id AND o.status <> 'DRAFT'
          AND tstzrange(o.effective_from, o.effective_to) && tstzrange(NEW.effective_from, NEW.effective_to)) THEN
        RAISE EXCEPTION 'recipe version % overlaps another version of recipe %', NEW.id, NEW.recipe_id
            USING ERRCODE = 'exclusion_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER recipe_version_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON inventory.recipe_version
    FOR EACH ROW
EXECUTE FUNCTION inventory.guard_recipe_version();

CREATE FUNCTION inventory.guard_recipe_ingredient() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    version_status VARCHAR(12);
BEGIN
    SELECT status INTO version_status FROM inventory.recipe_version
    WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.recipe_version_id ELSE NEW.recipe_version_id END;
    IF version_status IS DISTINCT FROM 'DRAFT'
        OR (TG_OP = 'UPDATE' AND OLD.recipe_version_id <> NEW.recipe_version_id) THEN
        RAISE EXCEPTION 'the ingredients of a % recipe version are immutable', version_status
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER recipe_ingredient_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON inventory.recipe_ingredient
    FOR EACH ROW
EXECUTE FUNCTION inventory.guard_recipe_ingredient();
