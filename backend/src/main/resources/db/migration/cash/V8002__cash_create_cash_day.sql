-- The cash side of a store's business day (spec §8.3, CONTEXT.md BusinessDay) and its reopenings (P19).
-- A day row is created by the first session of its business date with INSERT ... ON CONFLICT DO NOTHING, so the
-- id and the initial state have database defaults (uuidv7() is built into PostgreSQL 18).

-- Money facts are immutable (D18, D61): the same pattern as audit.audit_event, shared by the cash fact tables.
CREATE FUNCTION cash.reject_fact_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TABLE cash.cash_day
(
    id                           UUID                     NOT NULL DEFAULT uuidv7(),
    store_id                     UUID                     NOT NULL,
    business_date                DATE                     NOT NULL,
    status                       VARCHAR(25)              NOT NULL DEFAULT 'OPEN',
    version                      BIGINT                   NOT NULL DEFAULT 0,
    opened_at                    TIMESTAMP WITH TIME ZONE NOT NULL,
    operationally_closed_at      TIMESTAMP WITH TIME ZONE,
    operationally_closed_by_id   UUID,
    operationally_closed_by_kind VARCHAR(10),
    financially_closed_at        TIMESTAMP WITH TIME ZONE,
    financially_closed_by_id     UUID,
    financially_closed_by_kind   VARCHAR(10),
    CONSTRAINT cash_day_pk PRIMARY KEY (id),
    CONSTRAINT cash_day_store_date_uk UNIQUE (store_id, business_date),
    -- target of the same-store foreign keys of sessions, movements and closings
    CONSTRAINT cash_day_id_store_uk UNIQUE (id, store_id),
    CONSTRAINT cash_day_status_ck CHECK (status IN ('OPEN', 'OPERATIONALLY_CLOSED', 'FINANCIALLY_CLOSED')),
    CONSTRAINT cash_day_operational_close_ck CHECK (
        (status = 'OPEN') = (operationally_closed_at IS NULL)
            AND (operationally_closed_at IS NULL) = (operationally_closed_by_id IS NULL)
            AND (operationally_closed_at IS NULL) = (operationally_closed_by_kind IS NULL)),
    CONSTRAINT cash_day_financial_close_ck CHECK (
        (status = 'FINANCIALLY_CLOSED') = (financially_closed_at IS NOT NULL)
            AND (financially_closed_at IS NULL) = (financially_closed_by_id IS NULL)
            AND (financially_closed_at IS NULL) = (financially_closed_by_kind IS NULL)),
    CONSTRAINT cash_day_actor_kinds_ck CHECK (
        coalesce(operationally_closed_by_kind, 'ACCOUNT') IN ('ACCOUNT', 'EMPLOYEE')
            AND coalesce(financially_closed_by_kind, 'ACCOUNT') IN ('ACCOUNT', 'EMPLOYEE'))
);

-- spec §8.3: OPEN -> OPERATIONALLY_CLOSED -> FINANCIALLY_CLOSED, and the reopen OPERATIONALLY_CLOSED -> OPEN (P19).
-- A financially closed day is final; a day is never deleted.
CREATE FUNCTION cash.guard_cash_day_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'cash.cash_day rows are never deleted' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.store_id <> OLD.store_id OR NEW.business_date <> OLD.business_date THEN
        RAISE EXCEPTION 'cash.cash_day store and business date never change' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'OPEN' AND NEW.status = 'OPERATIONALLY_CLOSED')
            OR (OLD.status = 'OPERATIONALLY_CLOSED' AND NEW.status IN ('FINANCIALLY_CLOSED', 'OPEN'))) THEN
        RAISE EXCEPTION 'cash.cash_day cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'FINANCIALLY_CLOSED' THEN
        RAISE EXCEPTION 'cash.cash_day % is financially closed', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cash_day_guarded
    BEFORE UPDATE OR DELETE
    ON cash.cash_day
    FOR EACH ROW
EXECUTE FUNCTION cash.guard_cash_day_change();

CREATE TABLE cash.cash_day_reopening
(
    id                       UUID                     NOT NULL,
    cash_day_id              UUID                     NOT NULL,
    store_id                 UUID                     NOT NULL,
    reopened_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_id                 UUID                     NOT NULL,
    actor_kind               VARCHAR(10)              NOT NULL,
    approver_employee_id     UUID                     NOT NULL,
    reason                   TEXT                     NOT NULL,
    previous_closed_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    previous_closed_by_id    UUID                     NOT NULL,
    previous_closed_by_kind  VARCHAR(10)              NOT NULL,
    CONSTRAINT cash_day_reopening_pk PRIMARY KEY (id),
    CONSTRAINT cash_day_reopening_day_fk FOREIGN KEY (cash_day_id, store_id)
        REFERENCES cash.cash_day (id, store_id),
    CONSTRAINT cash_day_reopening_reason_not_blank_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT cash_day_reopening_kinds_ck CHECK (
        actor_kind IN ('ACCOUNT', 'EMPLOYEE') AND previous_closed_by_kind IN ('ACCOUNT', 'EMPLOYEE'))
);
CREATE INDEX cash_day_reopening_day_ix ON cash.cash_day_reopening (cash_day_id);

CREATE TRIGGER cash_day_reopening_append_only
    BEFORE UPDATE OR DELETE
    ON cash.cash_day_reopening
    FOR EACH ROW
EXECUTE FUNCTION cash.reject_fact_change();

CREATE TRIGGER cash_day_reopening_no_truncate
    BEFORE TRUNCATE
    ON cash.cash_day_reopening
    FOR EACH STATEMENT
EXECUTE FUNCTION cash.reject_fact_change();
