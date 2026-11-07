-- Goods receipts (D125, P51, §8.4 "apenas a receção confirmada altera stock"): per warehouse, optionally against a
-- submitted purchase order, with the supplier's document reference and the invoiced costs (no separate supplier
-- invoice). DRAFT → CONFIRMED (a CAS: only the confirmation posts RECEIPT movements) or DRAFT → CANCELLED. Partial
-- receipts are separate receipts. business_date is the store's business day of the confirmation.
CREATE TABLE inventory.goods_receipt
(
    id                     UUID                     NOT NULL,
    store_id               UUID                     NOT NULL,
    warehouse_id           UUID                     NOT NULL,
    supplier_id            UUID                     NOT NULL,
    purchase_order_id      UUID,
    supplier_document_ref  VARCHAR(60),
    status                 VARCHAR(10)              NOT NULL,
    received_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by_account_id  UUID,
    confirmed_at           TIMESTAMP WITH TIME ZONE,
    confirmed_by_account_id UUID,
    business_date          DATE,
    cancelled_at           TIMESTAMP WITH TIME ZONE,
    version                BIGINT                   NOT NULL,
    CONSTRAINT goods_receipt_pk PRIMARY KEY (id),
    CONSTRAINT goods_receipt_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT goods_receipt_supplier_fk FOREIGN KEY (supplier_id) REFERENCES inventory.supplier (id),
    CONSTRAINT goods_receipt_order_fk FOREIGN KEY (purchase_order_id) REFERENCES inventory.purchase_order (id),
    CONSTRAINT goods_receipt_status_ck CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT goods_receipt_confirmed_ck CHECK ((status = 'CONFIRMED')
        = (confirmed_at IS NOT NULL AND business_date IS NOT NULL)),
    CONSTRAINT goods_receipt_cancelled_ck CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
CREATE INDEX goods_receipt_store_ix ON inventory.goods_receipt (store_id, status, business_date);
CREATE INDEX goods_receipt_order_ix ON inventory.goods_receipt (purchase_order_id);

-- pack_quantity of the pack (or of the base unit without one), quantity_base in the base unit, and the unit cost per
-- base unit at scale 6 (D125). One line per stock item, so the receipt is one ledger source.
CREATE TABLE inventory.goods_receipt_line
(
    id                     UUID           NOT NULL,
    receipt_id             UUID           NOT NULL,
    purchase_order_line_id UUID,
    stock_item_id          UUID           NOT NULL,
    pack_id                UUID,
    pack_quantity          NUMERIC(19, 6) NOT NULL,
    quantity_base          NUMERIC(19, 6) NOT NULL,
    unit_cost_base         NUMERIC(19, 6) NOT NULL,
    CONSTRAINT goods_receipt_line_pk PRIMARY KEY (id),
    CONSTRAINT goods_receipt_line_item_uk UNIQUE (receipt_id, stock_item_id),
    CONSTRAINT goods_receipt_line_receipt_fk FOREIGN KEY (receipt_id) REFERENCES inventory.goods_receipt (id),
    CONSTRAINT goods_receipt_line_order_line_fk FOREIGN KEY (purchase_order_line_id)
        REFERENCES inventory.purchase_order_line (id),
    CONSTRAINT goods_receipt_line_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT goods_receipt_line_pack_fk FOREIGN KEY (pack_id) REFERENCES inventory.stock_item_pack (id),
    CONSTRAINT goods_receipt_line_quantity_ck CHECK (pack_quantity > 0 AND quantity_base > 0),
    CONSTRAINT goods_receipt_line_unit_cost_ck CHECK (unit_cost_base >= 0)
);
CREATE INDEX goods_receipt_line_order_line_ix ON inventory.goods_receipt_line (purchase_order_line_id);

CREATE TRIGGER goods_receipt_line_draft_only
    BEFORE INSERT OR UPDATE OR DELETE
    ON inventory.goods_receipt_line
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_line_change_unless_draft('goods_receipt', 'receipt_id');

CREATE TRIGGER goods_receipt_line_no_truncate
    BEFORE TRUNCATE
    ON inventory.goods_receipt_line
    FOR EACH STATEMENT
EXECUTE FUNCTION inventory.reject_fact_change();
