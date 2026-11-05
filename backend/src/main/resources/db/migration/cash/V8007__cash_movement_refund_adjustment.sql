-- R3 cash movements (D79, A23): a REFUND is money paid back out of an OPEN session for a settlement Refund, and an
-- ADJUSTMENT is a later, audited correction on a CLOSED session (spec §8.3 "correções posteriores são movimentos
-- auditados; não reescrever contagem original"). Adjustments come from the BackOffice, so the actor may be an
-- account: exactly one of the two actor columns is set.
ALTER TABLE cash.cash_movement
    DROP CONSTRAINT cash_movement_type_ck,
    DROP CONSTRAINT cash_movement_direction_ck,
    DROP CONSTRAINT cash_movement_cash_method_ck,
    DROP CONSTRAINT cash_movement_source_ck,
    DROP CONSTRAINT cash_movement_manual_reason_ck,
    ALTER COLUMN actor_employee_id DROP NOT NULL,
    ADD COLUMN actor_account_id UUID;

ALTER TABLE cash.cash_movement
    ADD CONSTRAINT cash_movement_type_ck CHECK (type IN ('OPENING_FLOAT', 'PAYMENT_RECEIPT', 'CASH_IN', 'CASH_OUT',
                                                         'EXPENSE', 'REFUND', 'ADJUSTMENT')),
    ADD CONSTRAINT cash_movement_direction_ck CHECK (
        (type IN ('OPENING_FLOAT', 'PAYMENT_RECEIPT', 'CASH_IN') AND direction = 'IN')
            OR (type IN ('CASH_OUT', 'EXPENSE', 'REFUND') AND direction = 'OUT')
            OR (type = 'ADJUSTMENT' AND direction IN ('IN', 'OUT'))),
    -- A receipt, a refund and an adjustment may be of any method (D52); the drawer movements are cash.
    ADD CONSTRAINT cash_movement_cash_method_ck CHECK (
        type IN ('PAYMENT_RECEIPT', 'REFUND', 'ADJUSTMENT') OR method_code = 'CASH'),
    -- Only settlement's confirmed Payment makes a receipt and only its Refund makes a refund (spec §7.7).
    ADD CONSTRAINT cash_movement_source_ck CHECK (
        (type = 'PAYMENT_RECEIPT' AND source_type = 'PAYMENT' AND source_id IS NOT NULL)
            OR (type = 'REFUND' AND source_type = 'REFUND' AND source_id IS NOT NULL)
            OR (type NOT IN ('PAYMENT_RECEIPT', 'REFUND') AND source_type IS NULL AND source_id IS NULL)),
    ADD CONSTRAINT cash_movement_manual_reason_ck CHECK (
        type NOT IN ('CASH_IN', 'CASH_OUT', 'EXPENSE', 'ADJUSTMENT') OR btrim(coalesce(reason, '')) <> ''),
    ADD CONSTRAINT cash_movement_one_actor_ck CHECK (num_nonnulls(actor_employee_id, actor_account_id) = 1);

-- Money moves only into an OPEN session; an ADJUSTMENT only onto a CLOSED one whose day is not FINANCIALLY_CLOSED
-- (backstop: the services lock the session or the day first).
CREATE OR REPLACE FUNCTION cash.require_open_session() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.type = 'ADJUSTMENT' THEN
        IF NOT EXISTS (SELECT 1
                       FROM cash.cash_session s
                                JOIN cash.cash_day d ON d.id = s.cash_day_id
                       WHERE s.id = NEW.session_id
                         AND s.status = 'CLOSED'
                         AND d.status <> 'FINANCIALLY_CLOSED') THEN
            RAISE EXCEPTION 'cash.cash_session % takes no adjustment: it is open or its day is financially closed',
                NEW.session_id USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM cash.cash_session WHERE id = NEW.session_id AND status = 'OPEN') THEN
        RAISE EXCEPTION 'cash.cash_session % is not open', NEW.session_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
