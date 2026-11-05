-- D69: a payment attempt, a payment and an allocation pay a TARGET: the ORDER (its residual once partitioned), one
-- of its bill PARTITIONs, or a customer's CREDIT_ACCOUNT (S29). Existing rows are ORDER targets through the DEFAULT:
-- metadata only, no fact row is updated (D93), so the append-only triggers never fire.
ALTER TABLE settlement.payment_attempt
    ADD COLUMN target_type       VARCHAR(15) NOT NULL DEFAULT 'ORDER',
    ADD COLUMN partition_id      UUID,
    ADD COLUMN credit_account_id UUID,
    ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE settlement.payment_attempt
    ADD COLUMN target_key UUID GENERATED ALWAYS AS (coalesce(partition_id, order_id, credit_account_id)) STORED,
    ADD CONSTRAINT payment_attempt_target_type_ck CHECK (target_type IN ('ORDER', 'PARTITION', 'CREDIT_ACCOUNT')),
    -- a partition belongs to its order, so a PARTITION target keeps the order id
    ADD CONSTRAINT payment_attempt_target_ck CHECK (
        (target_type = 'ORDER' AND order_id IS NOT NULL AND partition_id IS NULL AND credit_account_id IS NULL)
            OR (target_type = 'PARTITION' AND order_id IS NOT NULL AND partition_id IS NOT NULL
            AND credit_account_id IS NULL)
            OR (target_type = 'CREDIT_ACCOUNT' AND order_id IS NULL AND partition_id IS NULL
            AND credit_account_id IS NOT NULL));

-- One open attempt per target (D69), replacing R2's one per order: a doubt on partition A does not block B.
DROP INDEX settlement.payment_attempt_open_per_order_uk;
CREATE UNIQUE INDEX payment_attempt_open_per_target_uk ON settlement.payment_attempt (target_type, target_key)
    WHERE status IN ('CREATED', 'PENDING', 'UNKNOWN');
CREATE INDEX payment_attempt_partition_ix ON settlement.payment_attempt (partition_id) WHERE partition_id IS NOT NULL;

-- The payment carries its attempt's target, and, for the reporting facts (D84, D85), the attempt's terminal and the
-- business day of its cash session's day. R2 rows keep NULL there (their attempt has the terminal).
ALTER TABLE settlement.payment
    ADD COLUMN target_type       VARCHAR(15) NOT NULL DEFAULT 'ORDER',
    ADD COLUMN partition_id      UUID,
    ADD COLUMN credit_account_id UUID,
    ADD COLUMN terminal_id       UUID,
    ADD COLUMN business_date     DATE,
    ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE settlement.payment
    ADD COLUMN target_key UUID GENERATED ALWAYS AS (coalesce(partition_id, order_id, credit_account_id)) STORED,
    ADD CONSTRAINT payment_target_type_ck CHECK (target_type IN ('ORDER', 'PARTITION', 'CREDIT_ACCOUNT')),
    ADD CONSTRAINT payment_target_ck CHECK (
        (target_type = 'ORDER' AND order_id IS NOT NULL AND partition_id IS NULL AND credit_account_id IS NULL)
            OR (target_type = 'PARTITION' AND order_id IS NOT NULL AND partition_id IS NOT NULL
            AND credit_account_id IS NULL)
            OR (target_type = 'CREDIT_ACCOUNT' AND order_id IS NULL AND partition_id IS NULL
            AND credit_account_id IS NOT NULL));
CREATE INDEX payment_partition_ix ON settlement.payment (partition_id) WHERE partition_id IS NOT NULL;
CREATE INDEX payment_store_confirmed_ix ON settlement.payment (store_id, confirmed_at);

-- An allocation's (target_type, target_id) is its target key: the order, the partition, or (S29) a debit entry of a
-- credit account. A debt payment may allocate to several targets, so UNIQUE(payment_id) becomes one per target.
ALTER TABLE settlement.payment_allocation ALTER COLUMN target_type TYPE VARCHAR(15);
ALTER TABLE settlement.payment_allocation DROP CONSTRAINT payment_allocation_payment_uk;
ALTER TABLE settlement.payment_allocation DROP CONSTRAINT payment_allocation_target_ck;
ALTER TABLE settlement.payment_allocation
    ADD CONSTRAINT payment_allocation_target_uk UNIQUE (payment_id, target_type, target_id),
    ADD CONSTRAINT payment_allocation_target_ck CHECK (target_type IN ('ORDER', 'PARTITION', 'CREDIT_ACCOUNT'));
CREATE INDEX payment_allocation_target_ix ON settlement.payment_allocation (target_type, target_id);

-- Σ allocations of a payment = its amount, checked at commit (D69): a payment with no allocation, too little or too
-- much fails the transaction that writes it.
CREATE FUNCTION settlement.check_payment_allocation_sum() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    paid      NUMERIC(19, 4);
    allocated NUMERIC(19, 4);
BEGIN
    SELECT p.amount INTO paid FROM settlement.payment p WHERE p.id = NEW.payment_id;
    SELECT coalesce(sum(a.amount), 0) INTO allocated
    FROM settlement.payment_allocation a WHERE a.payment_id = NEW.payment_id;
    IF allocated <> paid THEN
        RAISE EXCEPTION 'payment % allocates % of %', NEW.payment_id, allocated, paid
            USING ERRCODE = 'check_violation', CONSTRAINT = 'payment_allocation_sum';
    END IF;
    RETURN NULL;
END;
$$;

-- On payment rows NEW.payment_id does not exist: a separate entry point reads NEW.id.
CREATE FUNCTION settlement.check_payment_allocated() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    allocated NUMERIC(19, 4);
BEGIN
    SELECT coalesce(sum(a.amount), 0) INTO allocated
    FROM settlement.payment_allocation a WHERE a.payment_id = NEW.id;
    IF allocated <> NEW.amount THEN
        RAISE EXCEPTION 'payment % allocates % of %', NEW.id, allocated, NEW.amount
            USING ERRCODE = 'check_violation', CONSTRAINT = 'payment_allocation_sum';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER payment_allocation_sum
    AFTER INSERT
    ON settlement.payment_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION settlement.check_payment_allocation_sum();

CREATE CONSTRAINT TRIGGER payment_allocated
    AFTER INSERT
    ON settlement.payment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION settlement.check_payment_allocated();
