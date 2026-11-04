-- Direct cash sessions (P16) and their blind counts (spec §8.3).

-- target of the same-store foreign keys of sessions and closings
ALTER TABLE cash.cash_register
    ADD CONSTRAINT cash_register_id_store_uk UNIQUE (id, store_id);

CREATE TABLE cash.cash_session
(
    id                    UUID                     NOT NULL,
    store_id              UUID                     NOT NULL,
    register_id           UUID                     NOT NULL,
    cash_day_id           UUID                     NOT NULL,
    operator_employee_id  UUID                     NOT NULL,
    terminal_id           UUID                     NOT NULL,
    status                VARCHAR(10)              NOT NULL,
    opening_float         NUMERIC(19, 4)           NOT NULL,
    currency              CHAR(3)                  NOT NULL,
    opened_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at             TIMESTAMP WITH TIME ZONE,
    closed_by_employee_id UUID,
    version               BIGINT                   NOT NULL,
    CONSTRAINT cash_session_pk PRIMARY KEY (id),
    CONSTRAINT cash_session_register_fk FOREIGN KEY (register_id, store_id)
        REFERENCES cash.cash_register (id, store_id),
    CONSTRAINT cash_session_day_fk FOREIGN KEY (cash_day_id, store_id) REFERENCES cash.cash_day (id, store_id),
    -- target of cash_movement_session_fk: a movement belongs to its session's day, register and store
    CONSTRAINT cash_session_scope_uk UNIQUE (id, cash_day_id, register_id, store_id),
    CONSTRAINT cash_session_status_ck CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT cash_session_opening_float_ck CHECK (opening_float >= 0),
    CONSTRAINT cash_session_closed_ck CHECK (
        (status = 'CLOSED') = (closed_at IS NOT NULL)
            AND (closed_at IS NULL) = (closed_by_employee_id IS NULL))
);
-- P16: one OPEN session per register and per operator, so a payment routes to exactly one session.
CREATE UNIQUE INDEX cash_session_register_open_uk ON cash.cash_session (register_id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX cash_session_operator_open_uk ON cash.cash_session (store_id, operator_employee_id)
    WHERE status = 'OPEN';
CREATE INDEX cash_session_day_ix ON cash.cash_session (cash_day_id, status);
CREATE INDEX cash_session_register_day_ix ON cash.cash_session (register_id, cash_day_id);

-- A closed session is history: it never reopens or changes, and no session is deleted.
CREATE FUNCTION cash.guard_cash_session_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'cash.cash_session %: % is not allowed', OLD.id, TG_OP USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cash_session_guarded
    BEFORE UPDATE OR DELETE
    ON cash.cash_session
    FOR EACH ROW
EXECUTE FUNCTION cash.guard_cash_session_change();

CREATE TABLE cash.cash_session_count
(
    id          UUID                     NOT NULL,
    session_id  UUID                     NOT NULL,
    method_code VARCHAR(30)              NOT NULL,
    amount      NUMERIC(19, 4)           NOT NULL,
    currency    CHAR(3)                  NOT NULL,
    counted_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT cash_session_count_pk PRIMARY KEY (id),
    CONSTRAINT cash_session_count_session_fk FOREIGN KEY (session_id) REFERENCES cash.cash_session (id),
    CONSTRAINT cash_session_count_method_uk UNIQUE (session_id, method_code),
    CONSTRAINT cash_session_count_amount_ck CHECK (amount >= 0),
    CONSTRAINT cash_session_count_method_not_blank_ck CHECK (btrim(method_code) <> '')
);

-- spec §8.3 "não reescrever contagem original"
CREATE TRIGGER cash_session_count_append_only
    BEFORE UPDATE OR DELETE
    ON cash.cash_session_count
    FOR EACH ROW
EXECUTE FUNCTION cash.reject_fact_change();

CREATE TRIGGER cash_session_count_no_truncate
    BEFORE TRUNCATE
    ON cash.cash_session_count
    FOR EACH STATEMENT
EXECUTE FUNCTION cash.reject_fact_change();
