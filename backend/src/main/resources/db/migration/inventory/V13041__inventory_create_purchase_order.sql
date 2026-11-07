-- Purchase orders (D124): per store and warehouse, to one supplier. DRAFT → SUBMITTED → CLOSED; DRAFT or SUBMITTED →
-- CANCELLED. Receipt progress (NONE, PARTIAL, FULL) is derived from confirmed receipts and never stored. An order
-- changes no stock.
CREATE TABLE inventory.purchase_order
(
    id                    UUID                     NOT NULL,
    store_id              UUID                     NOT NULL,
    warehouse_id          UUID                     NOT NULL,
    supplier_id           UUID                     NOT NULL,
    number                VARCHAR(30)              NOT NULL,
    status                VARCHAR(10)              NOT NULL,
    expected_at           DATE,
    notes                 TEXT,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by_account_id UUID,
    submitted_at          TIMESTAMP WITH TIME ZONE,
    closed_at             TIMESTAMP WITH TIME ZONE,
    cancelled_at          TIMESTAMP WITH TIME ZONE,
    version               BIGINT                   NOT NULL,
    CONSTRAINT purchase_order_pk PRIMARY KEY (id),
    CONSTRAINT purchase_order_number_uk UNIQUE (store_id, number),
    CONSTRAINT purchase_order_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT purchase_order_supplier_fk FOREIGN KEY (supplier_id) REFERENCES inventory.supplier (id),
    CONSTRAINT purchase_order_status_ck CHECK (status IN ('DRAFT', 'SUBMITTED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT purchase_order_number_ck CHECK (btrim(number) <> ''),
    CONSTRAINT purchase_order_submitted_ck CHECK (status IN ('DRAFT', 'CANCELLED') OR submitted_at IS NOT NULL),
    CONSTRAINT purchase_order_closed_ck CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT purchase_order_cancelled_ck CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
CREATE INDEX purchase_order_store_ix ON inventory.purchase_order (store_id, status);

-- quantity is in the purchase unit (the pack, or the base unit without one); quantity_base and unit_cost (per base
-- unit) are what receipts are compared and defaulted against. One line per stock item.
CREATE TABLE inventory.purchase_order_line
(
    id                UUID           NOT NULL,
    purchase_order_id UUID           NOT NULL,
    stock_item_id     UUID           NOT NULL,
    pack_id           UUID,
    quantity          NUMERIC(19, 6) NOT NULL,
    quantity_base     NUMERIC(19, 6) NOT NULL,
    unit_cost         NUMERIC(19, 6) NOT NULL,
    CONSTRAINT purchase_order_line_pk PRIMARY KEY (id),
    CONSTRAINT purchase_order_line_item_uk UNIQUE (purchase_order_id, stock_item_id),
    CONSTRAINT purchase_order_line_order_fk FOREIGN KEY (purchase_order_id) REFERENCES inventory.purchase_order (id),
    CONSTRAINT purchase_order_line_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT purchase_order_line_pack_fk FOREIGN KEY (pack_id) REFERENCES inventory.stock_item_pack (id),
    CONSTRAINT purchase_order_line_quantity_ck CHECK (quantity > 0 AND quantity_base > 0),
    CONSTRAINT purchase_order_line_unit_cost_ck CHECK (unit_cost >= 0)
);

-- The lines of a document are editable only while it is a DRAFT (§8: a submitted order, a confirmed receipt or
-- return is a fact). Shared by the order, receipt and return lines.
CREATE FUNCTION inventory.reject_line_change_unless_draft() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    parent_status TEXT;
BEGIN
    EXECUTE format('SELECT status FROM inventory.%I WHERE id = $1::uuid', TG_ARGV[0])
        INTO parent_status
        USING CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ->> TG_ARGV[1] ELSE to_jsonb(NEW) ->> TG_ARGV[1] END;
    IF parent_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION '%.% belongs to a % % and is immutable: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME,
            parent_status, TG_ARGV[0], TG_OP USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND (to_jsonb(OLD) ->> TG_ARGV[1]) IS DISTINCT FROM (to_jsonb(NEW) ->> TG_ARGV[1]) THEN
        RAISE EXCEPTION '%.% cannot move to another %', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_ARGV[0]
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER purchase_order_line_draft_only
    BEFORE INSERT OR UPDATE OR DELETE
    ON inventory.purchase_order_line
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_line_change_unless_draft('purchase_order', 'purchase_order_id');

CREATE TRIGGER purchase_order_line_no_truncate
    BEFORE TRUNCATE
    ON inventory.purchase_order_line
    FOR EACH STATEMENT
EXECUTE FUNCTION inventory.reject_fact_change();
