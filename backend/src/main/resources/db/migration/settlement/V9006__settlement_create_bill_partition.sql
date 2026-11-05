-- BillPartition, "conta dividida" (spec §4, §8.1, §8.2; D67, D68): an explicit, immutable allocation of an order's
-- bill. ITEMS takes quantities of order lines (its lines); AMOUNT takes a fixed amount of the residual (equal parts
-- are AMOUNT partitions sharing a group). LIVE -> CANCELLED is its only change; a payment, an open attempt or a
-- document makes it IN USE (checked under order_settlement). The financial state and "invoiced" are derived.
-- order_id, store_id and the employees refer to other modules by id only (D6).
CREATE TABLE settlement.bill_partition
(
    id                       UUID                     NOT NULL,
    order_id                 UUID                     NOT NULL,
    store_id                 UUID                     NOT NULL,
    kind                     VARCHAR(10)              NOT NULL,
    amount                   NUMERIC(19, 4),
    currency                 CHAR(3)                  NOT NULL,
    group_id                 UUID,
    part_no                  INT,
    parts                    INT,
    label                    VARCHAR(100),
    status                   VARCHAR(10)              NOT NULL,
    created_by_employee_id   UUID                     NOT NULL,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelled_at             TIMESTAMP WITH TIME ZONE,
    cancelled_by_employee_id UUID,
    cancel_reason            VARCHAR(500),
    version                  BIGINT                   NOT NULL,
    CONSTRAINT bill_partition_pk PRIMARY KEY (id),
    CONSTRAINT bill_partition_kind_ck CHECK (kind IN ('ITEMS', 'AMOUNT')),
    CONSTRAINT bill_partition_status_ck CHECK (status IN ('LIVE', 'CANCELLED')),
    -- AMOUNT has its amount; ITEMS has lines instead
    CONSTRAINT bill_partition_amount_ck CHECK ((kind = 'AMOUNT') = (amount IS NOT NULL) AND (amount IS NULL OR amount > 0)),
    -- equal parts: part part_no of parts, AMOUNT only, all three or none
    CONSTRAINT bill_partition_group_ck CHECK (
        (group_id IS NULL AND part_no IS NULL AND parts IS NULL)
            OR (group_id IS NOT NULL AND kind = 'AMOUNT' AND parts BETWEEN 2 AND 50 AND part_no BETWEEN 1 AND parts)),
    CONSTRAINT bill_partition_group_part_uk UNIQUE (group_id, part_no),
    CONSTRAINT bill_partition_cancel_ck CHECK (
        (status = 'LIVE' AND cancelled_at IS NULL AND cancelled_by_employee_id IS NULL AND cancel_reason IS NULL)
            OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by_employee_id IS NOT NULL
            AND btrim(coalesce(cancel_reason, '')) <> ''))
);
CREATE INDEX bill_partition_live_order_ix ON settlement.bill_partition (order_id) WHERE status = 'LIVE';
CREATE INDEX bill_partition_order_ix ON settlement.bill_partition (order_id);

CREATE TABLE settlement.bill_partition_line
(
    id            UUID           NOT NULL,
    partition_id  UUID           NOT NULL,
    order_line_id UUID           NOT NULL,
    quantity      NUMERIC(12, 3) NOT NULL,
    gross         NUMERIC(19, 4) NOT NULL,
    net           NUMERIC(19, 4) NOT NULL,
    tax           NUMERIC(19, 4) NOT NULL,
    tax_code      VARCHAR(30),
    tax_rate      NUMERIC(7, 4)  NOT NULL,
    currency      CHAR(3)        NOT NULL,
    CONSTRAINT bill_partition_line_pk PRIMARY KEY (id),
    CONSTRAINT bill_partition_line_partition_fk FOREIGN KEY (partition_id) REFERENCES settlement.bill_partition (id),
    CONSTRAINT bill_partition_line_uk UNIQUE (partition_id, order_line_id),
    CONSTRAINT bill_partition_line_quantity_ck CHECK (quantity > 0),
    CONSTRAINT bill_partition_line_amounts_ck CHECK (gross = net + tax AND net >= 0 AND tax >= 0),
    CONSTRAINT bill_partition_line_rate_ck CHECK (tax_rate >= 0)
);
CREATE INDEX bill_partition_line_order_line_ix ON settlement.bill_partition_line (order_line_id);

-- Lines are facts of their partition: never updated or deleted.
CREATE TRIGGER bill_partition_line_append_only
    BEFORE UPDATE OR DELETE
    ON settlement.bill_partition_line
    FOR EACH ROW
EXECUTE FUNCTION settlement.reject_fact_change();

CREATE TRIGGER bill_partition_line_no_truncate
    BEFORE TRUNCATE
    ON settlement.bill_partition_line
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();

-- A partition is never deleted, and its only update is LIVE -> CANCELLED (with who, when, why and the version).
CREATE FUNCTION settlement.guard_bill_partition_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    mutable CONSTANT TEXT[] := ARRAY ['status', 'cancelled_at', 'cancelled_by_employee_id', 'cancel_reason',
        'version'];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'settlement.bill_partition is never deleted' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'LIVE' OR NEW.status <> 'CANCELLED'
        OR (to_jsonb(OLD) - mutable) IS DISTINCT FROM (to_jsonb(NEW) - mutable) THEN
        RAISE EXCEPTION 'settlement.bill_partition % only goes LIVE -> CANCELLED', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bill_partition_immutable
    BEFORE UPDATE OR DELETE
    ON settlement.bill_partition
    FOR EACH ROW
EXECUTE FUNCTION settlement.guard_bill_partition_change();

CREATE TRIGGER bill_partition_no_truncate
    BEFORE TRUNCATE
    ON settlement.bill_partition
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();
