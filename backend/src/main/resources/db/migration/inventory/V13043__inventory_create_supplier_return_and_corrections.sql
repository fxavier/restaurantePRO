-- Returns to a supplier (D125): SUPPLIER_RETURN OUTs, valued at the average (D113); a return linked to a receipt
-- keeps that receipt line's cost for reference and returns at most what the line received less what was returned.
-- DRAFT → CONFIRMED or DRAFT → CANCELLED.
CREATE TABLE inventory.supplier_return
(
    id                      UUID                     NOT NULL,
    store_id                UUID                     NOT NULL,
    warehouse_id            UUID                     NOT NULL,
    supplier_id             UUID                     NOT NULL,
    goods_receipt_id        UUID,
    reason                  TEXT,
    status                  VARCHAR(10)              NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by_account_id   UUID,
    confirmed_at            TIMESTAMP WITH TIME ZONE,
    confirmed_by_account_id UUID,
    business_date           DATE,
    cancelled_at            TIMESTAMP WITH TIME ZONE,
    version                 BIGINT                   NOT NULL,
    CONSTRAINT supplier_return_pk PRIMARY KEY (id),
    CONSTRAINT supplier_return_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT supplier_return_supplier_fk FOREIGN KEY (supplier_id) REFERENCES inventory.supplier (id),
    CONSTRAINT supplier_return_receipt_fk FOREIGN KEY (goods_receipt_id) REFERENCES inventory.goods_receipt (id),
    CONSTRAINT supplier_return_status_ck CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT supplier_return_confirmed_ck CHECK ((status = 'CONFIRMED')
        = (confirmed_at IS NOT NULL AND business_date IS NOT NULL)),
    CONSTRAINT supplier_return_cancelled_ck CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
CREATE INDEX supplier_return_store_ix ON inventory.supplier_return (store_id, status);

CREATE TABLE inventory.supplier_return_line
(
    id              UUID           NOT NULL,
    return_id       UUID           NOT NULL,
    receipt_line_id UUID,
    stock_item_id   UUID           NOT NULL,
    quantity_base   NUMERIC(19, 6) NOT NULL,
    CONSTRAINT supplier_return_line_pk PRIMARY KEY (id),
    CONSTRAINT supplier_return_line_item_uk UNIQUE (return_id, stock_item_id),
    CONSTRAINT supplier_return_line_return_fk FOREIGN KEY (return_id) REFERENCES inventory.supplier_return (id),
    CONSTRAINT supplier_return_line_receipt_line_fk FOREIGN KEY (receipt_line_id)
        REFERENCES inventory.goods_receipt_line (id),
    CONSTRAINT supplier_return_line_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT supplier_return_line_quantity_ck CHECK (quantity_base > 0)
);
CREATE INDEX supplier_return_line_receipt_line_ix ON inventory.supplier_return_line (receipt_line_id);

CREATE TRIGGER supplier_return_line_draft_only
    BEFORE INSERT OR UPDATE OR DELETE
    ON inventory.supplier_return_line
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_line_change_unless_draft('supplier_return', 'return_id');

CREATE TRIGGER supplier_return_line_no_truncate
    BEFORE TRUNCATE
    ON inventory.supplier_return_line
    FOR EACH STATEMENT
EXECUTE FUNCTION inventory.reject_fact_change();

-- A later price difference on a confirmed receipt line (D125, P51): a fact, never an edit of the receipt. The share
-- still on hand (revalued_quantity) is a REVALUATION of revaluation_value; the consumed rest, variance_value, is a
-- cost variance. seq orders the corrections of one line (each starts from the previous new cost).
CREATE TABLE inventory.receipt_cost_correction
(
    id                 UUID                     NOT NULL,
    receipt_id         UUID                     NOT NULL,
    receipt_line_id    UUID                     NOT NULL,
    seq                INTEGER                  NOT NULL,
    previous_unit_cost NUMERIC(19, 6)           NOT NULL,
    new_unit_cost      NUMERIC(19, 6)           NOT NULL,
    reason             TEXT                     NOT NULL,
    revalued_quantity  NUMERIC(19, 6)           NOT NULL,
    revaluation_value  NUMERIC(19, 4)           NOT NULL,
    variance_value     NUMERIC(19, 4)           NOT NULL,
    business_date      DATE                     NOT NULL,
    actor_kind         VARCHAR(10)              NOT NULL,
    actor_id           UUID,
    at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT receipt_cost_correction_pk PRIMARY KEY (id),
    CONSTRAINT receipt_cost_correction_seq_uk UNIQUE (receipt_line_id, seq),
    CONSTRAINT receipt_cost_correction_receipt_fk FOREIGN KEY (receipt_id) REFERENCES inventory.goods_receipt (id),
    CONSTRAINT receipt_cost_correction_line_fk FOREIGN KEY (receipt_line_id)
        REFERENCES inventory.goods_receipt_line (id),
    CONSTRAINT receipt_cost_correction_seq_ck CHECK (seq > 0),
    CONSTRAINT receipt_cost_correction_costs_ck CHECK (previous_unit_cost >= 0 AND new_unit_cost >= 0),
    CONSTRAINT receipt_cost_correction_reason_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT receipt_cost_correction_quantity_ck CHECK (revalued_quantity >= 0)
);

-- The consumed share of a cost correction, for the margin report (D125, D131): one row per correction that has one.
CREATE TABLE inventory.cost_variance
(
    id            UUID           NOT NULL,
    store_id      UUID           NOT NULL,
    warehouse_id  UUID           NOT NULL,
    stock_item_id UUID           NOT NULL,
    business_date DATE           NOT NULL,
    value         NUMERIC(19, 4) NOT NULL,
    currency      CHAR(3)        NOT NULL,
    correction_id UUID           NOT NULL,
    CONSTRAINT cost_variance_pk PRIMARY KEY (id),
    CONSTRAINT cost_variance_correction_uk UNIQUE (correction_id),
    CONSTRAINT cost_variance_correction_fk FOREIGN KEY (correction_id)
        REFERENCES inventory.receipt_cost_correction (id),
    CONSTRAINT cost_variance_value_ck CHECK (value <> 0)
);
CREATE INDEX cost_variance_store_date_ix ON inventory.cost_variance (store_id, business_date);

CREATE TRIGGER receipt_cost_correction_append_only
    BEFORE UPDATE OR DELETE
    ON inventory.receipt_cost_correction
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_fact_change();

CREATE TRIGGER receipt_cost_correction_no_truncate
    BEFORE TRUNCATE
    ON inventory.receipt_cost_correction
    FOR EACH STATEMENT
EXECUTE FUNCTION inventory.reject_fact_change();

CREATE TRIGGER cost_variance_append_only
    BEFORE UPDATE OR DELETE
    ON inventory.cost_variance
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_fact_change();

CREATE TRIGGER cost_variance_no_truncate
    BEFORE TRUNCATE
    ON inventory.cost_variance
    FOR EACH STATEMENT
EXECUTE FUNCTION inventory.reject_fact_change();
