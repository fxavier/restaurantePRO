-- A Refund (spec §8.3 "Devolução é Refund com referência ao pagamento"; D77, P30): money given back from a confirmed
-- payment, to its own method, by the refunder (operator_employee_id) in the refunder's OPEN cash session, vouched for
-- by an approver who is another employee. Never a negative payment. A fact: the database refuses UPDATE, DELETE and
-- TRUNCATE. Σ refunds of a payment never exceeds the payment (a deferred constraint trigger, checked at commit). The
-- target repeats the payment's (an ORDER, one of its PARTITIONs, or a customer's CREDIT_ACCOUNT for a debt payment).
-- store_id, order_id, partition_id, credit_account_id, session_id, terminal_id and the employees refer to other
-- modules (or to settlement rows) by id only (D6). business_date is the refunder's session's cash day (D85).
CREATE TABLE settlement.refund
(
    id                   UUID                     NOT NULL,
    payment_id           UUID                     NOT NULL,
    store_id             UUID                     NOT NULL,
    target_type          VARCHAR(15)              NOT NULL,
    order_id             UUID,
    partition_id         UUID,
    credit_account_id    UUID,
    method_code          VARCHAR(30)              NOT NULL,
    amount               NUMERIC(19, 4)           NOT NULL,
    currency             CHAR(3)                  NOT NULL,
    reason               VARCHAR(500)             NOT NULL,
    external_reference   VARCHAR(100),
    session_id           UUID                     NOT NULL,
    terminal_id          UUID                     NOT NULL,
    business_date        DATE                     NOT NULL,
    operator_employee_id UUID                     NOT NULL,
    approver_employee_id UUID                     NOT NULL,
    confirmed_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT refund_pk PRIMARY KEY (id),
    CONSTRAINT refund_payment_fk FOREIGN KEY (payment_id) REFERENCES settlement.payment (id),
    CONSTRAINT refund_amount_ck CHECK (amount > 0),
    CONSTRAINT refund_reason_ck CHECK (btrim(reason) <> ''),
    -- P30: money leaves only with two people
    CONSTRAINT refund_approver_ck CHECK (approver_employee_id <> operator_employee_id),
    CONSTRAINT refund_target_type_ck CHECK (target_type IN ('ORDER', 'PARTITION', 'CREDIT_ACCOUNT')),
    CONSTRAINT refund_target_ck CHECK (
        (target_type = 'ORDER' AND order_id IS NOT NULL AND partition_id IS NULL AND credit_account_id IS NULL)
            OR (target_type = 'PARTITION' AND order_id IS NOT NULL AND partition_id IS NOT NULL
            AND credit_account_id IS NULL)
            OR (target_type = 'CREDIT_ACCOUNT' AND credit_account_id IS NOT NULL AND order_id IS NULL
            AND partition_id IS NULL))
);
CREATE INDEX refund_payment_ix ON settlement.refund (payment_id);
CREATE INDEX refund_order_ix ON settlement.refund (order_id);
-- Reporting's backfill: a store's refunds by business day.
CREATE INDEX refund_store_day_ix ON settlement.refund (store_id, business_date);

-- Σ refunds of a payment <= the payment, at commit (two refunds in one transaction are summed together).
CREATE FUNCTION settlement.check_refund_within_payment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    paid     NUMERIC(19, 4);
    refunded NUMERIC(19, 4);
BEGIN
    SELECT p.amount INTO paid FROM settlement.payment p WHERE p.id = NEW.payment_id;
    SELECT coalesce(sum(r.amount), 0) INTO refunded FROM settlement.refund r WHERE r.payment_id = NEW.payment_id;
    IF refunded > paid THEN
        RAISE EXCEPTION 'payment % refunds % of %', NEW.payment_id, refunded, paid
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refund_within_payment';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER refund_within_payment
    AFTER INSERT
    ON settlement.refund
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION settlement.check_refund_within_payment();

CREATE TRIGGER refund_append_only
    BEFORE UPDATE OR DELETE
    ON settlement.refund
    FOR EACH ROW
EXECUTE FUNCTION settlement.reject_fact_change();

CREATE TRIGGER refund_no_truncate
    BEFORE TRUNCATE
    ON settlement.refund
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();
