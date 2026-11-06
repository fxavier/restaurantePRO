-- CreditCharge, a credit sale (spec §7.7, §8.3 "crédito autorizado"; D74): an amount of an order's target (the
-- ORDER residual or one of its PARTITIONs) put on a customer's account. Never money received: no payment and no
-- cash movement. RESERVED holds the customer's limit (customers.credit_reservation, same id) while the target's
-- document is issued; POSTED put the debit on the ledger; RELEASED gave the limit back before the document was
-- issued. order_id, store_id, customer_id, document_id, ledger_entry_id and the employees refer to other modules by
-- id only (D6).
CREATE TABLE settlement.credit_charge
(
    id                      UUID                     NOT NULL,
    order_id                UUID                     NOT NULL,
    partition_id            UUID,
    target_type             VARCHAR(15)              NOT NULL,
    store_id                UUID                     NOT NULL,
    terminal_id             UUID                     NOT NULL,
    customer_id             UUID                     NOT NULL,
    amount                  NUMERIC(19, 4)           NOT NULL,
    currency                CHAR(3)                  NOT NULL,
    business_date           DATE                     NOT NULL,
    status                  VARCHAR(10)              NOT NULL,
    document_id             UUID,
    ledger_entry_id         UUID,
    created_by_employee_id  UUID                     NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    posted_by_employee_id   UUID,
    posted_at               TIMESTAMP WITH TIME ZONE,
    released_by_employee_id UUID,
    released_at             TIMESTAMP WITH TIME ZONE,
    release_reason          VARCHAR(500),
    version                 BIGINT                   NOT NULL,
    CONSTRAINT credit_charge_pk PRIMARY KEY (id),
    CONSTRAINT credit_charge_partition_fk FOREIGN KEY (partition_id) REFERENCES settlement.bill_partition (id),
    CONSTRAINT credit_charge_target_type_ck CHECK (target_type IN ('ORDER', 'PARTITION')),
    CONSTRAINT credit_charge_target_ck CHECK ((target_type = 'PARTITION') = (partition_id IS NOT NULL)),
    CONSTRAINT credit_charge_amount_ck CHECK (amount > 0),
    CONSTRAINT credit_charge_status_ck CHECK (status IN ('RESERVED', 'POSTED', 'RELEASED')),
    -- POSTED => its issued document and its ledger debit; RELEASED => who, when and why
    CONSTRAINT credit_charge_posted_ck CHECK (status <> 'POSTED'
        OR (document_id IS NOT NULL AND ledger_entry_id IS NOT NULL AND posted_at IS NOT NULL)),
    CONSTRAINT credit_charge_released_ck CHECK ((status = 'RELEASED') = (released_at IS NOT NULL)
        AND (status <> 'RELEASED' OR (released_by_employee_id IS NOT NULL
            AND btrim(coalesce(release_reason, '')) <> '')))
);
ALTER TABLE settlement.credit_charge
    ADD COLUMN target_key UUID GENERATED ALWAYS AS (coalesce(partition_id, order_id)) STORED;

-- At most one live (RESERVED or POSTED) charge per target (D74).
CREATE UNIQUE INDEX credit_charge_live_per_target_uk ON settlement.credit_charge (target_type, target_key)
    WHERE status IN ('RESERVED', 'POSTED');
-- The sweeper's work: RESERVED charges, oldest first.
CREATE INDEX credit_charge_reserved_ix ON settlement.credit_charge (status, created_at) WHERE status = 'RESERVED';
CREATE INDEX credit_charge_order_ix ON settlement.credit_charge (order_id);
-- Reporting's backfill: a store's POSTED charges by business day.
CREATE INDEX credit_charge_store_day_ix ON settlement.credit_charge (store_id, business_date) WHERE status = 'POSTED';

-- A charge is never deleted, and its only updates are the document of a RESERVED charge and RESERVED -> POSTED or
-- RESERVED -> RELEASED: a POSTED or RELEASED charge is a fact.
CREATE FUNCTION settlement.guard_credit_charge_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    -- target_key is generated: a BEFORE trigger sees it NULL in NEW, and it follows the immutable target anyway
    mutable CONSTANT TEXT[] := ARRAY ['status', 'document_id', 'ledger_entry_id', 'posted_by_employee_id',
        'posted_at', 'released_by_employee_id', 'released_at', 'release_reason', 'version', 'target_key'];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'settlement.credit_charge is never deleted' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'RESERVED'
        OR (OLD.document_id IS NOT NULL AND NEW.document_id IS DISTINCT FROM OLD.document_id)
        OR (to_jsonb(OLD) - mutable) IS DISTINCT FROM (to_jsonb(NEW) - mutable) THEN
        RAISE EXCEPTION 'settlement.credit_charge % only goes RESERVED -> POSTED or RELEASED', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_charge_immutable
    BEFORE UPDATE OR DELETE
    ON settlement.credit_charge
    FOR EACH ROW
EXECUTE FUNCTION settlement.guard_credit_charge_change();

CREATE TRIGGER credit_charge_no_truncate
    BEFORE TRUNCATE
    ON settlement.credit_charge
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();

-- A debt payment (D75) may name the debits it settles: an attempt that is not cash keeps them until it succeeds.
ALTER TABLE settlement.payment_attempt
    ADD COLUMN debit_entry_ids UUID[];
