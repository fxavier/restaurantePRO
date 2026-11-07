-- The consumption policy (D116): per catalog family (NULL = every other family), whether a sale consumes stock and
-- when (ROUND_CONFIRMED, the only trigger in R4). Versioned like recipes: a line uses the version effective at its
-- confirmation. Version 1, active from the epoch, tracks every family.
CREATE TABLE inventory.consumption_policy_version
(
    id             UUID                     NOT NULL,
    version_no     INTEGER                  NOT NULL,
    status         VARCHAR(12)              NOT NULL,
    effective_from TIMESTAMP WITH TIME ZONE,
    effective_to   TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_at   TIMESTAMP WITH TIME ZONE,
    version        BIGINT                   NOT NULL,
    CONSTRAINT consumption_policy_version_pk PRIMARY KEY (id),
    CONSTRAINT consumption_policy_version_no_uk UNIQUE (version_no),
    CONSTRAINT consumption_policy_version_status_ck CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED')),
    CONSTRAINT consumption_policy_version_effective_ck CHECK (
        (status = 'DRAFT' AND effective_from IS NULL AND effective_to IS NULL)
            OR (status = 'ACTIVE' AND effective_from IS NOT NULL AND effective_to IS NULL)
            OR (status = 'SUPERSEDED' AND effective_from IS NOT NULL AND effective_to > effective_from))
);
CREATE UNIQUE INDEX consumption_policy_one_active_uk ON inventory.consumption_policy_version ((true))
    WHERE status = 'ACTIVE';

CREATE TABLE inventory.consumption_policy_rule
(
    id                UUID        NOT NULL,
    policy_version_id UUID        NOT NULL,
    family_id         UUID,
    trigger           VARCHAR(20) NOT NULL,
    tracked           BOOLEAN     NOT NULL,
    CONSTRAINT consumption_policy_rule_pk PRIMARY KEY (id),
    CONSTRAINT consumption_policy_rule_version_fk FOREIGN KEY (policy_version_id)
        REFERENCES inventory.consumption_policy_version (id),
    CONSTRAINT consumption_policy_rule_trigger_ck CHECK (trigger IN ('ROUND_CONFIRMED'))
);
CREATE UNIQUE INDEX consumption_policy_rule_family_uk ON inventory.consumption_policy_rule (policy_version_id,
    coalesce(family_id, '00000000-0000-0000-0000-000000000000'));

-- As recipes: a used version and its rules never change; periods never overlap.
CREATE FUNCTION inventory.guard_consumption_policy_version() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'consumption policy version % is % and cannot be deleted', OLD.id, OLD.status
                USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status <> 'DRAFT' AND (
        NEW.effective_from IS DISTINCT FROM OLD.effective_from
            OR NEW.version_no IS DISTINCT FROM OLD.version_no
            OR NOT (NEW.status = OLD.status OR (OLD.status = 'ACTIVE' AND NEW.status = 'SUPERSEDED'))
            OR (OLD.status = 'SUPERSEDED' AND NEW.effective_to IS DISTINCT FROM OLD.effective_to)) THEN
        RAISE EXCEPTION 'consumption policy version % is % and immutable', OLD.id, OLD.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status <> 'DRAFT' AND EXISTS (
        SELECT 1 FROM inventory.consumption_policy_version o
        WHERE o.id <> NEW.id AND o.status <> 'DRAFT'
          AND tstzrange(o.effective_from, o.effective_to) && tstzrange(NEW.effective_from, NEW.effective_to)) THEN
        RAISE EXCEPTION 'consumption policy version % overlaps another version', NEW.id
            USING ERRCODE = 'exclusion_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER consumption_policy_version_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON inventory.consumption_policy_version
    FOR EACH ROW
EXECUTE FUNCTION inventory.guard_consumption_policy_version();

CREATE FUNCTION inventory.guard_consumption_policy_rule() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    version_status VARCHAR(12);
BEGIN
    SELECT status INTO version_status FROM inventory.consumption_policy_version
    WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.policy_version_id ELSE NEW.policy_version_id END;
    IF version_status IS DISTINCT FROM 'DRAFT'
        OR (TG_OP = 'UPDATE' AND OLD.policy_version_id <> NEW.policy_version_id) THEN
        RAISE EXCEPTION 'the rules of a % consumption policy version are immutable', version_status
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER consumption_policy_rule_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON inventory.consumption_policy_rule
    FOR EACH ROW
EXECUTE FUNCTION inventory.guard_consumption_policy_rule();

-- Version 1: the rule is inserted while the version is DRAFT, then the version is activated.
INSERT INTO inventory.consumption_policy_version (id, version_no, status, created_at, version)
VALUES ('00000000-0000-7000-8000-00000000c001', 1, 'DRAFT', now(), 0);
INSERT INTO inventory.consumption_policy_rule (id, policy_version_id, family_id, trigger, tracked)
VALUES ('00000000-0000-7000-8000-00000000c002', '00000000-0000-7000-8000-00000000c001', NULL, 'ROUND_CONFIRMED',
        true);
UPDATE inventory.consumption_policy_version
SET status = 'ACTIVE', effective_from = '1970-01-01T00:00:00Z', activated_at = now()
WHERE id = '00000000-0000-7000-8000-00000000c001';
