-- Stock counts (D119) and the opening count (D122), S49. A count of one warehouse goes
-- DRAFT → IN_PROGRESS → SUBMITTED → APPROVED | REJECTED, or DRAFT | IN_PROGRESS → CANCELLED. Approval posts the
-- differences through the ledger (COUNT_ADJUSTMENT, or OPENING at the entered cost) at each line's counted_at.
-- A decided count is a fact: its row and lines never change again, and a rejected count is kept.
CREATE TABLE inventory.stock_count
(
    id                    UUID                     NOT NULL,
    store_id              UUID                     NOT NULL,
    warehouse_id          UUID                     NOT NULL,
    kind                  VARCHAR(10)              NOT NULL,
    scope                 VARCHAR(4)               NOT NULL,
    status                VARCHAR(12)              NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by_account_id UUID,
    started_at            TIMESTAMP WITH TIME ZONE,
    submitted_at          TIMESTAMP WITH TIME ZONE,
    decided_at            TIMESTAMP WITH TIME ZONE,
    decided_by_account_id UUID,
    reject_reason         TEXT,
    cancelled_at          TIMESTAMP WITH TIME ZONE,
    version               BIGINT                   NOT NULL,
    CONSTRAINT stock_count_pk PRIMARY KEY (id),
    CONSTRAINT stock_count_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT stock_count_kind_ck CHECK (kind IN ('OPENING', 'REGULAR')),
    CONSTRAINT stock_count_scope_ck CHECK (scope IN ('ALL', 'LIST')),
    CONSTRAINT stock_count_status_ck CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'SUBMITTED', 'APPROVED', 'REJECTED',
                                                       'CANCELLED')),
    CONSTRAINT stock_count_started_ck CHECK (
        status IN ('DRAFT', 'CANCELLED') OR started_at IS NOT NULL),
    CONSTRAINT stock_count_submitted_ck CHECK (
        (status IN ('SUBMITTED', 'APPROVED', 'REJECTED')) = (submitted_at IS NOT NULL)),
    CONSTRAINT stock_count_decided_ck CHECK (
        (status IN ('APPROVED', 'REJECTED')) = (decided_at IS NOT NULL)),
    CONSTRAINT stock_count_rejected_ck CHECK (
        (status = 'REJECTED') = (reject_reason IS NOT NULL AND btrim(reject_reason) <> '')),
    CONSTRAINT stock_count_cancelled_ck CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
-- One live count per warehouse ("409 a live count exists").
CREATE UNIQUE INDEX stock_count_live_ux ON inventory.stock_count (warehouse_id)
    WHERE status IN ('DRAFT', 'IN_PROGRESS', 'SUBMITTED');
-- One opening per warehouse (422 OPENING_NOT_FIRST, also when two openings race).
CREATE UNIQUE INDEX stock_count_opening_ux ON inventory.stock_count (warehouse_id)
    WHERE kind = 'OPENING' AND status = 'APPROVED';
CREATE INDEX stock_count_store_ix ON inventory.stock_count (store_id, created_at);

-- counted_quantity is in the item's base unit (unit). expected_quantity and difference are written once, at
-- approval: expected = Σ movements of the item in the warehouse with occurred_at ≤ counted_at.
CREATE TABLE inventory.stock_count_line
(
    id                     UUID           NOT NULL,
    count_id               UUID           NOT NULL,
    stock_item_id          UUID           NOT NULL,
    counted_quantity       NUMERIC(19, 6),
    unit                   VARCHAR(8)     NOT NULL,
    counted_at             TIMESTAMP WITH TIME ZONE,
    counted_by_actor_kind  VARCHAR(10),
    counted_by_actor_id    UUID,
    unit_cost              NUMERIC(19, 6),
    expected_quantity      NUMERIC(19, 6),
    difference             NUMERIC(19, 6),
    CONSTRAINT stock_count_line_pk PRIMARY KEY (id),
    CONSTRAINT stock_count_line_count_fk FOREIGN KEY (count_id) REFERENCES inventory.stock_count (id),
    CONSTRAINT stock_count_line_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT stock_count_line_unit_fk FOREIGN KEY (unit) REFERENCES inventory.stock_unit (code),
    CONSTRAINT stock_count_line_item_uk UNIQUE (count_id, stock_item_id),
    CONSTRAINT stock_count_line_counted_ck CHECK (counted_quantity IS NULL OR counted_quantity >= 0),
    CONSTRAINT stock_count_line_counted_at_ck CHECK ((counted_quantity IS NULL) = (counted_at IS NULL)),
    CONSTRAINT stock_count_line_unit_cost_ck CHECK (unit_cost IS NULL OR unit_cost >= 0),
    CONSTRAINT stock_count_line_settled_ck CHECK (
        (expected_quantity IS NULL) = (difference IS NULL)
            AND (difference IS NULL OR difference = counted_quantity - expected_quantity))
);

-- Lines change only while the count is DRAFT or IN_PROGRESS; afterwards approval writes expected and difference
-- once, and nothing else. A unit cost belongs to an opening count only.
CREATE FUNCTION inventory.guard_stock_count_line() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    count_status VARCHAR(12);
    count_kind   VARCHAR(10);
BEGIN
    SELECT c.status, c.kind INTO count_status, count_kind
    FROM inventory.stock_count c
    WHERE c.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.count_id ELSE NEW.count_id END;
    IF TG_OP <> 'DELETE' AND NEW.unit_cost IS NOT NULL AND count_kind <> 'OPENING' THEN
        RAISE EXCEPTION 'inventory.stock_count_line: a unit cost is entered on an opening count only'
            USING ERRCODE = 'check_violation';
    END IF;
    IF count_status IN ('DRAFT', 'IN_PROGRESS') THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.expected_quantity IS NULL
        AND ROW (NEW.id, NEW.count_id, NEW.stock_item_id, NEW.counted_quantity, NEW.unit, NEW.counted_at,
                 NEW.counted_by_actor_kind, NEW.counted_by_actor_id, NEW.unit_cost)
            IS NOT DISTINCT FROM
            ROW (OLD.id, OLD.count_id, OLD.stock_item_id, OLD.counted_quantity, OLD.unit, OLD.counted_at,
                 OLD.counted_by_actor_kind, OLD.counted_by_actor_id, OLD.unit_cost) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'inventory.stock_count_line: the lines of a % count are immutable (%)', count_status, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER stock_count_line_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON inventory.stock_count_line
    FOR EACH ROW
EXECUTE FUNCTION inventory.guard_stock_count_line();

-- A decided or cancelled count is final; counts are never deleted.
CREATE FUNCTION inventory.guard_stock_count() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status IN ('APPROVED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION 'inventory.stock_count: a % count is final (%)', OLD.status, TG_OP
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER stock_count_final
    BEFORE UPDATE OR DELETE
    ON inventory.stock_count
    FOR EACH ROW
EXECUTE FUNCTION inventory.guard_stock_count();

CREATE TRIGGER stock_count_no_truncate
    BEFORE TRUNCATE
    ON inventory.stock_count
    FOR EACH STATEMENT
EXECUTE FUNCTION inventory.reject_fact_change();

CREATE TRIGGER stock_count_line_no_truncate
    BEFORE TRUNCATE
    ON inventory.stock_count_line
    FOR EACH STATEMENT
EXECUTE FUNCTION inventory.reject_fact_change();
