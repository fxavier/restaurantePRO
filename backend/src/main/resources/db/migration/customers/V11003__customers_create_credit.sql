-- The customer's credit ("conta corrente", spec §5, §7.7, §8.4; D73): account, limit-change facts, limit
-- reservations, the ledger and the settlements of debits by credits. The balance is never stored: it is
-- Σ DEBIT − Σ CREDIT over credit_ledger_entry, served by an index-only scan.

CREATE FUNCTION customers.reject_fact_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- One account per customer, company-wide (P38). Reservations lock this row FOR UPDATE (D73, D76).
CREATE TABLE customers.credit_account
(
    customer_id    UUID                     NOT NULL,
    credit_limit   NUMERIC(19, 4)           NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    credit_blocked BOOLEAN                  NOT NULL,
    blocked_reason TEXT,
    blocked_at     TIMESTAMP WITH TIME ZONE,
    version        BIGINT                   NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT credit_account_pk PRIMARY KEY (customer_id),
    CONSTRAINT credit_account_customer_fk FOREIGN KEY (customer_id) REFERENCES customers.customer (id),
    CONSTRAINT credit_account_limit_ck CHECK (credit_limit >= 0),
    CONSTRAINT credit_account_block_ck CHECK (
        (credit_blocked AND blocked_at IS NOT NULL AND btrim(coalesce(blocked_reason, '')) <> '')
            OR (NOT credit_blocked AND blocked_at IS NULL AND blocked_reason IS NULL))
);

-- A limit change is a fact with its reason and actor (spec §9: no endpoint sets a balance). old_limit is NULL
-- when the change opened the account.
CREATE TABLE customers.credit_limit_change
(
    id          UUID                     NOT NULL,
    customer_id UUID                     NOT NULL,
    old_limit   NUMERIC(19, 4),
    new_limit   NUMERIC(19, 4)           NOT NULL,
    reason      TEXT                     NOT NULL,
    actor_kind  VARCHAR(10)              NOT NULL,
    actor_id    UUID                     NOT NULL,
    at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT credit_limit_change_pk PRIMARY KEY (id),
    CONSTRAINT credit_limit_change_account_fk FOREIGN KEY (customer_id)
        REFERENCES customers.credit_account (customer_id),
    CONSTRAINT credit_limit_change_new_limit_ck CHECK (new_limit >= 0),
    CONSTRAINT credit_limit_change_reason_ck CHECK (btrim(reason) <> '')
);
CREATE INDEX credit_limit_change_customer_ix ON customers.credit_limit_change (customer_id, at);

-- Limit held for one credit charge until its debit is posted (CONSUMED) or the charge is cancelled (RELEASED).
CREATE TABLE customers.credit_reservation
(
    id          UUID                     NOT NULL,
    customer_id UUID                     NOT NULL,
    amount      NUMERIC(19, 4)           NOT NULL,
    currency    CHAR(3)                  NOT NULL,
    charge_id   UUID                     NOT NULL,
    status      VARCHAR(10)              NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT credit_reservation_pk PRIMARY KEY (id),
    CONSTRAINT credit_reservation_account_fk FOREIGN KEY (customer_id)
        REFERENCES customers.credit_account (customer_id),
    CONSTRAINT credit_reservation_charge_uk UNIQUE (charge_id),
    CONSTRAINT credit_reservation_amount_ck CHECK (amount > 0),
    CONSTRAINT credit_reservation_status_ck CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED')),
    CONSTRAINT credit_reservation_closed_ck CHECK ((status = 'ACTIVE') = (closed_at IS NULL))
);
CREATE INDEX credit_reservation_active_ix ON customers.credit_reservation (customer_id) INCLUDE (amount)
    WHERE status = 'ACTIVE';

-- The only change a reservation takes: closing an ACTIVE one, nothing else on the row.
CREATE FUNCTION customers.guard_reservation_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.status = 'ACTIVE' AND NEW.status IN ('CONSUMED', 'RELEASED')
        AND NEW.id = OLD.id AND NEW.customer_id = OLD.customer_id AND NEW.amount = OLD.amount
        AND NEW.currency = OLD.currency AND NEW.charge_id = OLD.charge_id AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'customers.credit_reservation: % of a % reservation is not allowed', TG_OP, OLD.status
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER credit_reservation_closes_once
    BEFORE UPDATE OR DELETE
    ON customers.credit_reservation
    FOR EACH ROW
EXECUTE FUNCTION customers.guard_reservation_change();

CREATE TRIGGER credit_reservation_no_truncate
    BEFORE TRUNCATE
    ON customers.credit_reservation
    FOR EACH STATEMENT
EXECUTE FUNCTION customers.reject_fact_change();

-- The ledger: a DEBIT for a credit sale (CREDIT_CHARGE) or the refund of a debt payment (REFUND), a CREDIT for a
-- debt payment (PAYMENT). One entry per source and kind, so a repeated post adds nothing.
CREATE TABLE customers.credit_ledger_entry
(
    id            UUID                     NOT NULL,
    customer_id   UUID                     NOT NULL,
    kind          VARCHAR(6)               NOT NULL,
    amount        NUMERIC(19, 4)           NOT NULL,
    currency      CHAR(3)                  NOT NULL,
    source_type   VARCHAR(15)              NOT NULL,
    source_id     UUID                     NOT NULL,
    document_id   UUID,
    store_id      UUID,
    business_date DATE,
    description   VARCHAR(200),
    occurred_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT credit_ledger_entry_pk PRIMARY KEY (id),
    CONSTRAINT credit_ledger_entry_account_fk FOREIGN KEY (customer_id)
        REFERENCES customers.credit_account (customer_id),
    CONSTRAINT credit_ledger_entry_kind_ck CHECK (kind IN ('DEBIT', 'CREDIT')),
    CONSTRAINT credit_ledger_entry_source_type_ck CHECK (source_type IN ('CREDIT_CHARGE', 'PAYMENT', 'REFUND')),
    CONSTRAINT credit_ledger_entry_kind_source_ck CHECK (
        (kind = 'DEBIT' AND source_type IN ('CREDIT_CHARGE', 'REFUND')) OR (kind = 'CREDIT' AND source_type = 'PAYMENT')),
    CONSTRAINT credit_ledger_entry_amount_ck CHECK (amount > 0),
    -- a credit sale is debited only once its document exists (D74 s3)
    CONSTRAINT credit_ledger_entry_sale_document_ck CHECK (source_type <> 'CREDIT_CHARGE' OR document_id IS NOT NULL),
    CONSTRAINT credit_ledger_entry_origin_ck CHECK (
        source_type = 'REFUND' OR (store_id IS NOT NULL AND business_date IS NOT NULL)),
    CONSTRAINT credit_ledger_entry_source_uk UNIQUE (source_type, source_id, kind)
);
-- balance and statement of a customer: an index-only Σ
CREATE INDEX credit_ledger_entry_customer_ix ON customers.credit_ledger_entry (customer_id, occurred_at)
    INCLUDE (kind, amount);

-- Which CREDIT settles which sale DEBIT, and for how much. A row with reversal_entry_id is the opposite: the refund
-- DEBIT (reversal_entry_id) of a debt payment reopens that much of what the CREDIT had settled on the debit.
CREATE TABLE customers.credit_settlement
(
    id                UUID           NOT NULL,
    credit_entry_id   UUID           NOT NULL,
    debit_entry_id    UUID           NOT NULL,
    reversal_entry_id UUID,
    amount            NUMERIC(19, 4) NOT NULL,
    CONSTRAINT credit_settlement_pk PRIMARY KEY (id),
    CONSTRAINT credit_settlement_credit_fk FOREIGN KEY (credit_entry_id)
        REFERENCES customers.credit_ledger_entry (id),
    CONSTRAINT credit_settlement_debit_fk FOREIGN KEY (debit_entry_id) REFERENCES customers.credit_ledger_entry (id),
    CONSTRAINT credit_settlement_reversal_fk FOREIGN KEY (reversal_entry_id)
        REFERENCES customers.credit_ledger_entry (id),
    CONSTRAINT credit_settlement_amount_ck CHECK (amount > 0),
    CONSTRAINT credit_settlement_uk UNIQUE NULLS NOT DISTINCT (credit_entry_id, debit_entry_id, reversal_entry_id)
);
CREATE INDEX credit_settlement_debit_ix ON customers.credit_settlement (debit_entry_id);
CREATE INDEX credit_settlement_reversal_ix ON customers.credit_settlement (reversal_entry_id)
    WHERE reversal_entry_id IS NOT NULL;

CREATE TRIGGER credit_limit_change_append_only
    BEFORE UPDATE OR DELETE
    ON customers.credit_limit_change
    FOR EACH ROW
EXECUTE FUNCTION customers.reject_fact_change();

CREATE TRIGGER credit_limit_change_no_truncate
    BEFORE TRUNCATE
    ON customers.credit_limit_change
    FOR EACH STATEMENT
EXECUTE FUNCTION customers.reject_fact_change();

CREATE TRIGGER credit_ledger_entry_append_only
    BEFORE UPDATE OR DELETE
    ON customers.credit_ledger_entry
    FOR EACH ROW
EXECUTE FUNCTION customers.reject_fact_change();

CREATE TRIGGER credit_ledger_entry_no_truncate
    BEFORE TRUNCATE
    ON customers.credit_ledger_entry
    FOR EACH STATEMENT
EXECUTE FUNCTION customers.reject_fact_change();

CREATE TRIGGER credit_settlement_append_only
    BEFORE UPDATE OR DELETE
    ON customers.credit_settlement
    FOR EACH ROW
EXECUTE FUNCTION customers.reject_fact_change();

CREATE TRIGGER credit_settlement_no_truncate
    BEFORE TRUNCATE
    ON customers.credit_settlement
    FOR EACH STATEMENT
EXECUTE FUNCTION customers.reject_fact_change();

-- Deferred sums (D73), checked at commit once the entry and all its settlement rows are written.
CREATE FUNCTION customers.require_credit_settled(credit_id UUID) RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    entry_amount NUMERIC;
    settled      NUMERIC;
BEGIN
    SELECT amount INTO entry_amount FROM customers.credit_ledger_entry WHERE id = credit_id;
    SELECT coalesce(sum(amount), 0) INTO settled
    FROM customers.credit_settlement
    WHERE credit_entry_id = credit_id AND reversal_entry_id IS NULL;
    IF settled <> entry_amount THEN
        RAISE EXCEPTION 'customers.credit_settlement: credit % of % settles %', credit_id, entry_amount, settled
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION customers.require_reversal_reopened(reversal_id UUID) RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    entry_amount NUMERIC;
    reopened     NUMERIC;
BEGIN
    SELECT amount INTO entry_amount FROM customers.credit_ledger_entry WHERE id = reversal_id;
    SELECT coalesce(sum(amount), 0) INTO reopened
    FROM customers.credit_settlement
    WHERE reversal_entry_id = reversal_id;
    IF reopened <> entry_amount THEN
        RAISE EXCEPTION 'customers.credit_settlement: refund % of % reopens %', reversal_id, entry_amount, reopened
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION customers.check_settlement() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    credit   customers.credit_ledger_entry;
    debit    customers.credit_ledger_entry;
    reversal customers.credit_ledger_entry;
    settled  NUMERIC;
    pair     NUMERIC;
BEGIN
    SELECT * INTO credit FROM customers.credit_ledger_entry WHERE id = NEW.credit_entry_id;
    SELECT * INTO debit FROM customers.credit_ledger_entry WHERE id = NEW.debit_entry_id;
    IF credit.kind <> 'CREDIT' OR debit.source_type <> 'CREDIT_CHARGE'
        OR credit.customer_id <> debit.customer_id THEN
        RAISE EXCEPTION 'customers.credit_settlement % does not link a credit to a sale debit of its customer', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.reversal_entry_id IS NOT NULL THEN
        SELECT * INTO reversal FROM customers.credit_ledger_entry WHERE id = NEW.reversal_entry_id;
        IF reversal.source_type <> 'REFUND' OR reversal.customer_id <> credit.customer_id THEN
            RAISE EXCEPTION 'customers.credit_settlement % reopens with an entry that is not a refund', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
        PERFORM customers.require_reversal_reopened(reversal.id);
    END IF;

    SELECT coalesce(sum(CASE WHEN reversal_entry_id IS NULL THEN amount ELSE -amount END), 0) INTO settled
    FROM customers.credit_settlement
    WHERE debit_entry_id = debit.id;
    IF settled < 0 OR settled > debit.amount THEN
        RAISE EXCEPTION 'customers.credit_settlement: debit % of % would be settled by %', debit.id, debit.amount,
            settled USING ERRCODE = 'check_violation';
    END IF;
    SELECT sum(CASE WHEN reversal_entry_id IS NULL THEN amount ELSE -amount END) INTO pair
    FROM customers.credit_settlement
    WHERE debit_entry_id = debit.id AND credit_entry_id = credit.id;
    IF pair < 0 THEN
        RAISE EXCEPTION 'customers.credit_settlement: more is reopened on debit % than credit % settled', debit.id,
            credit.id USING ERRCODE = 'check_violation';
    END IF;
    PERFORM customers.require_credit_settled(credit.id);
    RETURN NULL;
END;
$$;

-- A CREDIT and a refund are fully allocated; a sale debit consumed its own reservation (D73).
CREATE FUNCTION customers.check_ledger_entry() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.kind = 'CREDIT' THEN
        PERFORM customers.require_credit_settled(NEW.id);
    ELSIF NEW.source_type = 'REFUND' THEN
        PERFORM customers.require_reversal_reopened(NEW.id);
    ELSIF NOT EXISTS (SELECT 1
                      FROM customers.credit_reservation
                      WHERE charge_id = NEW.source_id AND customer_id = NEW.customer_id AND amount = NEW.amount
                        AND currency = NEW.currency AND status = 'CONSUMED') THEN
        RAISE EXCEPTION 'customers.credit_ledger_entry: sale debit % has no consumed reservation of its amount',
            NEW.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER credit_settlement_sums
    AFTER INSERT
    ON customers.credit_settlement
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION customers.check_settlement();

CREATE CONSTRAINT TRIGGER credit_ledger_entry_allocated
    AFTER INSERT
    ON customers.credit_ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION customers.check_ledger_entry();
