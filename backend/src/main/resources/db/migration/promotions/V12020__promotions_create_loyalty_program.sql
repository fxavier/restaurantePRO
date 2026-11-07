-- The company-wide loyalty program (spec §5 "Fidelização", P45, D111): versions DRAFT → PUBLISHED → RETIRED, each
-- with its earning rule (points per spend unit), the items or families that earn (none = every line) and the rewards
-- a member can redeem (D112). A PUBLISHED or RETIRED version is a fact: its rule, scope and rewards never change, so
-- every ledger entry that names it can be explained later.

CREATE SCHEMA IF NOT EXISTS promotions;

CREATE TABLE promotions.loyalty_program_version
(
    id                      UUID                     NOT NULL,
    version_no              INTEGER                  NOT NULL,
    status                  VARCHAR(10)              NOT NULL,
    spend_unit              NUMERIC(19, 4)           NOT NULL,
    currency                CHAR(3)                  NOT NULL,
    points_per_unit         INTEGER                  NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at            TIMESTAMP WITH TIME ZONE,
    published_by_account_id UUID,
    retired_at              TIMESTAMP WITH TIME ZONE,
    version                 BIGINT                   NOT NULL,
    CONSTRAINT loyalty_program_version_pk PRIMARY KEY (id),
    CONSTRAINT loyalty_program_version_no_uk UNIQUE (version_no),
    CONSTRAINT loyalty_program_version_no_ck CHECK (version_no > 0),
    CONSTRAINT loyalty_program_version_status_ck CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT loyalty_program_version_spend_unit_ck CHECK (spend_unit > 0),
    CONSTRAINT loyalty_program_version_points_ck CHECK (points_per_unit > 0),
    CONSTRAINT loyalty_program_version_published_ck CHECK (
        (status = 'DRAFT') = (published_at IS NULL) AND (published_at IS NULL) = (published_by_account_id IS NULL)),
    CONSTRAINT loyalty_program_version_retired_ck CHECK (
        (status = 'RETIRED') = (retired_at IS NOT NULL) AND (retired_at IS NULL OR retired_at >= published_at))
);
-- one program is in force at a time
CREATE UNIQUE INDEX loyalty_program_version_published_uk ON promotions.loyalty_program_version (status)
    WHERE status = 'PUBLISHED';

-- What earns under a version: an item or a family. No row = every line earns.
CREATE TABLE promotions.loyalty_program_scope
(
    version_id UUID NOT NULL,
    item_id    UUID,
    family_id  UUID,
    CONSTRAINT loyalty_program_scope_version_fk FOREIGN KEY (version_id)
        REFERENCES promotions.loyalty_program_version (id),
    CONSTRAINT loyalty_program_scope_one_ck CHECK ((item_id IS NULL) <> (family_id IS NULL)),
    CONSTRAINT loyalty_program_scope_uk UNIQUE NULLS NOT DISTINCT (version_id, item_id, family_id)
);

-- A reward of a version (D112): a free unit, or an amount off one unit, of an item, of a family's items, or (no scope)
-- of any item.
CREATE TABLE promotions.loyalty_reward
(
    id          UUID         NOT NULL,
    version_id  UUID         NOT NULL,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    points_cost INTEGER      NOT NULL,
    effect      VARCHAR(16)  NOT NULL,
    amount      NUMERIC(19, 4),
    currency    CHAR(3),
    item_id     UUID,
    family_id   UUID,
    CONSTRAINT loyalty_reward_pk PRIMARY KEY (id),
    CONSTRAINT loyalty_reward_version_fk FOREIGN KEY (version_id) REFERENCES promotions.loyalty_program_version (id),
    CONSTRAINT loyalty_reward_code_uk UNIQUE (version_id, code),
    CONSTRAINT loyalty_reward_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT loyalty_reward_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT loyalty_reward_cost_ck CHECK (points_cost > 0),
    CONSTRAINT loyalty_reward_effect_ck CHECK (effect IN ('FREE_UNIT', 'AMOUNT_OFF_UNIT')),
    CONSTRAINT loyalty_reward_amount_ck CHECK (
        (effect = 'FREE_UNIT' AND amount IS NULL AND currency IS NULL)
            OR (effect = 'AMOUNT_OFF_UNIT' AND amount > 0 AND currency IS NOT NULL)),
    CONSTRAINT loyalty_reward_scope_ck CHECK (item_id IS NULL OR family_id IS NULL)
);

-- A version changes only while DRAFT. Publishing (DRAFT → PUBLISHED) and retiring (PUBLISHED → RETIRED, only the status
-- and retired_at) are the only changes it takes afterwards; a published version is never deleted.
CREATE FUNCTION promotions.loyalty_guard_program_version() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.status = 'DRAFT' THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' AND OLD.status = 'DRAFT' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' AND NEW.status = 'RETIRED'
        AND NEW.id = OLD.id AND NEW.version_no = OLD.version_no AND NEW.spend_unit = OLD.spend_unit
        AND NEW.currency = OLD.currency AND NEW.points_per_unit = OLD.points_per_unit
        AND NEW.created_at = OLD.created_at AND NEW.published_at = OLD.published_at
        AND NEW.published_by_account_id = OLD.published_by_account_id THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'promotions.loyalty_program_version: % of a % version is not allowed', TG_OP, OLD.status
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER loyalty_program_version_frozen
    BEFORE UPDATE OR DELETE
    ON promotions.loyalty_program_version
    FOR EACH ROW
EXECUTE FUNCTION promotions.loyalty_guard_program_version();

-- Scope and rewards change only under a DRAFT version (the old and the new parent, for a row moved between versions).
CREATE FUNCTION promotions.loyalty_guard_program_child() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') AND NOT EXISTS (SELECT 1
                                                     FROM promotions.loyalty_program_version
                                                     WHERE id = OLD.version_id AND status = 'DRAFT') THEN
        RAISE EXCEPTION 'promotions.%: % under a version that is not DRAFT is not allowed', TG_TABLE_NAME, TG_OP
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') AND NOT EXISTS (SELECT 1
                                                     FROM promotions.loyalty_program_version
                                                     WHERE id = NEW.version_id AND status = 'DRAFT') THEN
        RAISE EXCEPTION 'promotions.%: % under a version that is not DRAFT is not allowed', TG_TABLE_NAME, TG_OP
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER loyalty_program_scope_frozen
    BEFORE INSERT OR UPDATE OR DELETE
    ON promotions.loyalty_program_scope
    FOR EACH ROW
EXECUTE FUNCTION promotions.loyalty_guard_program_child();

CREATE TRIGGER loyalty_reward_frozen
    BEFORE INSERT OR UPDATE OR DELETE
    ON promotions.loyalty_reward
    FOR EACH ROW
EXECUTE FUNCTION promotions.loyalty_guard_program_child();

CREATE FUNCTION promotions.loyalty_reject_truncate() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.% holds loyalty facts: TRUNCATE is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER loyalty_program_version_no_truncate
    BEFORE TRUNCATE
    ON promotions.loyalty_program_version
    FOR EACH STATEMENT
EXECUTE FUNCTION promotions.loyalty_reject_truncate();

CREATE TRIGGER loyalty_program_scope_no_truncate
    BEFORE TRUNCATE
    ON promotions.loyalty_program_scope
    FOR EACH STATEMENT
EXECUTE FUNCTION promotions.loyalty_reject_truncate();

CREATE TRIGGER loyalty_reward_no_truncate
    BEFORE TRUNCATE
    ON promotions.loyalty_reward
    FOR EACH STATEMENT
EXECUTE FUNCTION promotions.loyalty_reject_truncate();
