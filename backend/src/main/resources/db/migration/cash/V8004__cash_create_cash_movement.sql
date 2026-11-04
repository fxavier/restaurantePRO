-- Cash movements (spec §8.1, §8.2 "Referência de origem única impede duplicar movimentos", §7.5, §7.7).
CREATE TABLE cash.cash_movement
(
    id                UUID                     NOT NULL,
    store_id          UUID                     NOT NULL,
    cash_day_id       UUID                     NOT NULL,
    session_id        UUID                     NOT NULL,
    register_id       UUID                     NOT NULL,
    type              VARCHAR(20)              NOT NULL,
    direction         VARCHAR(3)               NOT NULL,
    method_code       VARCHAR(30)              NOT NULL,
    amount            NUMERIC(19, 4)           NOT NULL,
    currency          CHAR(3)                  NOT NULL,
    reason            TEXT,
    source_type       VARCHAR(20),
    source_id         UUID,
    actor_employee_id UUID                     NOT NULL,
    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT cash_movement_pk PRIMARY KEY (id),
    CONSTRAINT cash_movement_session_fk FOREIGN KEY (session_id, cash_day_id, register_id, store_id)
        REFERENCES cash.cash_session (id, cash_day_id, register_id, store_id),
    CONSTRAINT cash_movement_type_ck CHECK (type IN ('OPENING_FLOAT', 'PAYMENT_RECEIPT', 'CASH_IN', 'CASH_OUT',
                                                     'EXPENSE')),
    CONSTRAINT cash_movement_direction_ck CHECK (
        (type IN ('OPENING_FLOAT', 'PAYMENT_RECEIPT', 'CASH_IN') AND direction = 'IN')
            OR (type IN ('CASH_OUT', 'EXPENSE') AND direction = 'OUT')),
    CONSTRAINT cash_movement_amount_ck CHECK (amount > 0),
    CONSTRAINT cash_movement_method_not_blank_ck CHECK (btrim(method_code) <> ''),
    -- Only a payment receipt carries another method than CASH (D52: card totals are compared at closing too).
    CONSTRAINT cash_movement_cash_method_ck CHECK (type = 'PAYMENT_RECEIPT' OR method_code = 'CASH'),
    -- Only settlement's confirmed Payment creates a receipt (spec §7.7), and only a receipt has a source.
    CONSTRAINT cash_movement_source_ck CHECK (
        (type = 'PAYMENT_RECEIPT' AND source_type = 'PAYMENT' AND source_id IS NOT NULL)
            OR (type <> 'PAYMENT_RECEIPT' AND source_type IS NULL AND source_id IS NULL)),
    CONSTRAINT cash_movement_manual_reason_ck CHECK (
        type NOT IN ('CASH_IN', 'CASH_OUT', 'EXPENSE') OR btrim(coalesce(reason, '')) <> '')
);
-- One movement per payment (D §4): a retried or redelivered receipt never counts twice.
CREATE UNIQUE INDEX cash_movement_source_uk ON cash.cash_movement (source_type, source_id)
    WHERE source_id IS NOT NULL;
CREATE INDEX cash_movement_store_day_ix ON cash.cash_movement (store_id, cash_day_id);
CREATE INDEX cash_movement_session_ix ON cash.cash_movement (session_id);
CREATE INDEX cash_movement_register_day_ix ON cash.cash_movement (register_id, cash_day_id);

-- Money moves only into an OPEN session (backstop: the service reads the session FOR SHARE first).
CREATE FUNCTION cash.require_open_session() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM cash.cash_session WHERE id = NEW.session_id AND status = 'OPEN') THEN
        RAISE EXCEPTION 'cash.cash_session % is not open', NEW.session_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cash_movement_into_open_session
    BEFORE INSERT
    ON cash.cash_movement
    FOR EACH ROW
EXECUTE FUNCTION cash.require_open_session();

CREATE TRIGGER cash_movement_append_only
    BEFORE UPDATE OR DELETE
    ON cash.cash_movement
    FOR EACH ROW
EXECUTE FUNCTION cash.reject_fact_change();

CREATE TRIGGER cash_movement_no_truncate
    BEFORE TRUNCATE
    ON cash.cash_movement
    FOR EACH STATEMENT
EXECUTE FUNCTION cash.reject_fact_change();
