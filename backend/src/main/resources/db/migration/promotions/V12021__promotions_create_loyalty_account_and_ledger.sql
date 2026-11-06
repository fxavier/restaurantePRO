-- Loyalty members and their points ledger (spec §5 "Fidelização usa movimentos rastreáveis, incluindo reversões",
-- §8.4; D110, D112). The balance is never stored: it is Σ loyalty_point_entry.points, served by an index-only scan.
-- Every debit (redemption, expiry, negative adjustment) is allocated first-in-first-out to the point lots it consumes
-- (earnings, returned redemptions, positive adjustments), so an expiry period can be switched on later from the
-- remaining points of each lot without migrating data (P45). The allocation sums are checked at commit.

-- One account per customer, company-wide (P38). It holds no points. A redemption locks this row FOR UPDATE (D112,
-- D132: after orders.order_header and customers.credit_account), so two terminals never spend the same points.
-- No foreign key to customers.customer (D6): enrollment checks the customer through CustomersApi.
CREATE TABLE promotions.loyalty_account
(
    customer_id            UUID                     NOT NULL,
    status                 VARCHAR(10)              NOT NULL,
    suspended_reason       TEXT,
    enrolled_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    enrolled_by_actor_kind VARCHAR(10)              NOT NULL,
    enrolled_by_actor_id   UUID,
    version                BIGINT                   NOT NULL,
    CONSTRAINT loyalty_account_pk PRIMARY KEY (customer_id),
    CONSTRAINT loyalty_account_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT loyalty_account_suspended_ck CHECK (
        (status = 'SUSPENDED') = (btrim(coalesce(suspended_reason, '')) <> '')),
    CONSTRAINT loyalty_account_actor_ck CHECK (enrolled_by_actor_kind IN ('ACCOUNT', 'EMPLOYEE', 'SYSTEM')
        AND (enrolled_by_actor_kind = 'SYSTEM') = (enrolled_by_actor_id IS NULL))
);

-- A ledger entry: whole points, signed by kind. One entry per source and kind (UNIQUE), so a repeated earn, redemption
-- or reversal adds nothing; a reversal names the entry it reverses, at most once.
--   EARN            > 0  ORDER         an order closed (D111); eligible_gross is what earned
--   REDEEM          < 0  ORDER_LINE    a reward on one unit (D112); unit_price → new_unit_price
--   EARN_REVERSAL   < 0  ORDER         reverses an EARN
--   REDEEM_REVERSAL > 0  ORDER_LINE    the rewarded line was voided (void_id); returns the points
--   ADJUSTMENT      ≠ 0  ADJUSTMENT    manual, with a reason (LOYALTY_ADJUST, §8.4)
--   EXPIRY          < 0  EXPIRY        reserved for an expiry rule (P45: none by default)
CREATE TABLE promotions.loyalty_point_entry
(
    id                 UUID                     NOT NULL,
    customer_id        UUID                     NOT NULL,
    kind               VARCHAR(16)              NOT NULL,
    points             INTEGER                  NOT NULL,
    source_type        VARCHAR(12)              NOT NULL,
    source_id          UUID                     NOT NULL,
    reverses_entry_id  UUID,
    program_version_id UUID,
    reward_id          UUID,
    order_id           UUID,
    item_id            UUID,
    void_id            UUID,
    eligible_gross     NUMERIC(19, 4),
    unit_price         NUMERIC(19, 4),
    new_unit_price     NUMERIC(19, 4),
    currency           CHAR(3),
    store_id           UUID,
    business_date      DATE,
    reason             TEXT,
    actor_kind         VARCHAR(10)              NOT NULL,
    actor_id           UUID,
    occurred_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT loyalty_point_entry_pk PRIMARY KEY (id),
    CONSTRAINT loyalty_point_entry_account_fk FOREIGN KEY (customer_id)
        REFERENCES promotions.loyalty_account (customer_id),
    CONSTRAINT loyalty_point_entry_version_fk FOREIGN KEY (program_version_id)
        REFERENCES promotions.loyalty_program_version (id),
    CONSTRAINT loyalty_point_entry_reward_fk FOREIGN KEY (reward_id) REFERENCES promotions.loyalty_reward (id),
    CONSTRAINT loyalty_point_entry_reverses_fk FOREIGN KEY (reverses_entry_id)
        REFERENCES promotions.loyalty_point_entry (id),
    CONSTRAINT loyalty_point_entry_source_uk UNIQUE (source_type, source_id, kind),
    CONSTRAINT loyalty_point_entry_reverses_uk UNIQUE (reverses_entry_id),
    CONSTRAINT loyalty_point_entry_kind_ck CHECK (
        kind IN ('EARN', 'REDEEM', 'EARN_REVERSAL', 'REDEEM_REVERSAL', 'ADJUSTMENT', 'EXPIRY')),
    CONSTRAINT loyalty_point_entry_source_type_ck CHECK (source_type IN ('ORDER', 'ORDER_LINE', 'ADJUSTMENT', 'EXPIRY')),
    CONSTRAINT loyalty_point_entry_sign_ck CHECK (points <> 0 AND CASE kind
        WHEN 'EARN' THEN points > 0
        WHEN 'REDEEM_REVERSAL' THEN points > 0
        WHEN 'ADJUSTMENT' THEN true
        ELSE points < 0 END),
    CONSTRAINT loyalty_point_entry_kind_source_ck CHECK (CASE kind
        WHEN 'EARN' THEN source_type = 'ORDER'
        WHEN 'EARN_REVERSAL' THEN source_type = 'ORDER'
        WHEN 'REDEEM' THEN source_type = 'ORDER_LINE'
        WHEN 'REDEEM_REVERSAL' THEN source_type = 'ORDER_LINE'
        WHEN 'ADJUSTMENT' THEN source_type = 'ADJUSTMENT'
        ELSE source_type = 'EXPIRY' END),
    CONSTRAINT loyalty_point_entry_reversal_ck CHECK (
        (kind IN ('EARN_REVERSAL', 'REDEEM_REVERSAL')) = (reverses_entry_id IS NOT NULL)),
    CONSTRAINT loyalty_point_entry_earn_ck CHECK (kind <> 'EARN' OR (program_version_id IS NOT NULL
        AND order_id = source_id AND eligible_gross > 0 AND currency IS NOT NULL AND store_id IS NOT NULL
        AND business_date IS NOT NULL)),
    CONSTRAINT loyalty_point_entry_redeem_ck CHECK (kind <> 'REDEEM' OR (program_version_id IS NOT NULL
        AND reward_id IS NOT NULL AND order_id IS NOT NULL AND unit_price > 0 AND new_unit_price >= 0
        AND new_unit_price < unit_price AND currency IS NOT NULL AND store_id IS NOT NULL
        AND business_date IS NOT NULL)),
    CONSTRAINT loyalty_point_entry_redeem_reversal_ck CHECK (kind <> 'REDEEM_REVERSAL' OR void_id IS NOT NULL),
    CONSTRAINT loyalty_point_entry_reason_ck CHECK (kind <> 'ADJUSTMENT' OR btrim(coalesce(reason, '')) <> ''),
    CONSTRAINT loyalty_point_entry_actor_ck CHECK (actor_kind IN ('ACCOUNT', 'EMPLOYEE', 'SYSTEM')
        AND (actor_kind = 'SYSTEM') = (actor_id IS NULL))
);
-- balance and statement of a member: an index-only Σ (D110)
CREATE INDEX loyalty_point_entry_customer_ix ON promotions.loyalty_point_entry (customer_id, occurred_at)
    INCLUDE (points);

-- Which point lot (earn_entry_id: an entry with points > 0) a debit (debit_entry_id: points < 0) consumed, and how many.
CREATE TABLE promotions.loyalty_allocation
(
    debit_entry_id UUID    NOT NULL,
    earn_entry_id  UUID    NOT NULL,
    points         INTEGER NOT NULL,
    CONSTRAINT loyalty_allocation_pk PRIMARY KEY (debit_entry_id, earn_entry_id),
    CONSTRAINT loyalty_allocation_debit_fk FOREIGN KEY (debit_entry_id)
        REFERENCES promotions.loyalty_point_entry (id),
    CONSTRAINT loyalty_allocation_earn_fk FOREIGN KEY (earn_entry_id) REFERENCES promotions.loyalty_point_entry (id),
    CONSTRAINT loyalty_allocation_points_ck CHECK (points > 0)
);
CREATE INDEX loyalty_allocation_earn_ix ON promotions.loyalty_allocation (earn_entry_id) INCLUDE (points);

-- Ledger facts are never edited or deleted (spec §8): a correction is a new entry.
CREATE FUNCTION promotions.loyalty_reject_fact_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER loyalty_point_entry_append_only
    BEFORE UPDATE OR DELETE
    ON promotions.loyalty_point_entry
    FOR EACH ROW
EXECUTE FUNCTION promotions.loyalty_reject_fact_change();

CREATE TRIGGER loyalty_point_entry_no_truncate
    BEFORE TRUNCATE
    ON promotions.loyalty_point_entry
    FOR EACH STATEMENT
EXECUTE FUNCTION promotions.loyalty_reject_fact_change();

CREATE TRIGGER loyalty_allocation_append_only
    BEFORE UPDATE OR DELETE
    ON promotions.loyalty_allocation
    FOR EACH ROW
EXECUTE FUNCTION promotions.loyalty_reject_fact_change();

CREATE TRIGGER loyalty_allocation_no_truncate
    BEFORE TRUNCATE
    ON promotions.loyalty_allocation
    FOR EACH STATEMENT
EXECUTE FUNCTION promotions.loyalty_reject_fact_change();

-- A debit is fully allocated: Σ of its allocations = |points| (checked at commit, once every row is written).
CREATE FUNCTION promotions.loyalty_require_debit_allocated(debit_id UUID) RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    debit_points INTEGER;
    allocated    BIGINT;
BEGIN
    SELECT points INTO debit_points FROM promotions.loyalty_point_entry WHERE id = debit_id;
    SELECT coalesce(sum(points), 0) INTO allocated FROM promotions.loyalty_allocation WHERE debit_entry_id = debit_id;
    IF allocated <> -debit_points THEN
        RAISE EXCEPTION 'promotions.loyalty_allocation: debit % of % points has % allocated', debit_id,
            -debit_points, allocated USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION promotions.loyalty_check_allocation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    debit     promotions.loyalty_point_entry;
    lot       promotions.loyalty_point_entry;
    allocated BIGINT;
BEGIN
    SELECT * INTO debit FROM promotions.loyalty_point_entry WHERE id = NEW.debit_entry_id;
    SELECT * INTO lot FROM promotions.loyalty_point_entry WHERE id = NEW.earn_entry_id;
    IF debit.points >= 0 OR lot.points <= 0 OR debit.customer_id <> lot.customer_id
        OR lot.occurred_at > debit.occurred_at THEN
        RAISE EXCEPTION 'promotions.loyalty_allocation (%, %) does not link a debit to an earlier lot of its member',
            NEW.debit_entry_id, NEW.earn_entry_id USING ERRCODE = 'check_violation';
    END IF;
    SELECT coalesce(sum(points), 0) INTO allocated FROM promotions.loyalty_allocation WHERE earn_entry_id = lot.id;
    IF allocated > lot.points THEN
        RAISE EXCEPTION 'promotions.loyalty_allocation: lot % of % points would give %', lot.id, lot.points,
            allocated USING ERRCODE = 'check_violation';
    END IF;
    PERFORM promotions.loyalty_require_debit_allocated(debit.id);
    RETURN NULL;
END;
$$;

-- A debit is fully allocated; a reversal reverses an entry of its member of the matching kind, with the opposite points.
CREATE FUNCTION promotions.loyalty_check_entry() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    reversed      promotions.loyalty_point_entry;
    reversed_kind VARCHAR(16);
BEGIN
    IF NEW.points < 0 THEN
        PERFORM promotions.loyalty_require_debit_allocated(NEW.id);
    END IF;
    IF NEW.reverses_entry_id IS NOT NULL THEN
        SELECT * INTO reversed FROM promotions.loyalty_point_entry WHERE id = NEW.reverses_entry_id;
        reversed_kind := CASE NEW.kind WHEN 'EARN_REVERSAL' THEN 'EARN' ELSE 'REDEEM' END;
        IF reversed.customer_id <> NEW.customer_id OR reversed.points <> -NEW.points
            OR reversed.kind <> reversed_kind OR reversed.source_id <> NEW.source_id THEN
            RAISE EXCEPTION 'promotions.loyalty_point_entry: % % does not reverse entry % of its member', NEW.kind,
                NEW.id, NEW.reverses_entry_id USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER loyalty_allocation_sums
    AFTER INSERT
    ON promotions.loyalty_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION promotions.loyalty_check_allocation();

CREATE CONSTRAINT TRIGGER loyalty_point_entry_allocated
    AFTER INSERT
    ON promotions.loyalty_point_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION promotions.loyalty_check_entry();
