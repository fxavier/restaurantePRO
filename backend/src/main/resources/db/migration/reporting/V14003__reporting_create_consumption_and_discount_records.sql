-- Reporting projections of the cost of goods and of discounts (D131, D84 pattern): rows pulled from inventory's and
-- orders' read-only facades, written once (INSERT ... ON CONFLICT DO NOTHING keyed by the source) and deleted only by
-- a rebuild of a store and business-date range. Ids of other modules are plain columns (D6). business_date is the
-- order's business day for consumption, waste and discounts (D85), the posting day for cost variances.

-- One row per stock movement that is cost of goods: a sale's consumption (negative value), its reversal by a void
-- without waste (positive) and a manual WASTE (negative). sale_item_id and family_code are the catalog item sold
-- (a menu's component for a component's consumption) and its family in the release the line was sold under;
-- line_id is the round line the movement comes from.
CREATE TABLE reporting.consumption_record
(
    movement_id   UUID                     NOT NULL,
    store_id      UUID                     NOT NULL,
    business_date DATE                     NOT NULL,
    sale_item_id  UUID,
    family_code   VARCHAR(50),
    line_id       UUID,
    warehouse_id  UUID                     NOT NULL,
    stock_item_id UUID                     NOT NULL,
    type          VARCHAR(25)              NOT NULL,
    quantity      NUMERIC(19, 6)           NOT NULL,
    value         NUMERIC(19, 4)           NOT NULL,
    source_type   VARCHAR(30)              NOT NULL,
    source_id     UUID                     NOT NULL,
    projected_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT consumption_record_pk PRIMARY KEY (movement_id),
    CONSTRAINT consumption_record_type_ck CHECK (type IN ('SALE_CONSUMPTION', 'CONSUMPTION_REVERSAL', 'WASTE')),
    CONSTRAINT consumption_record_sign_ck CHECK (CASE type
        WHEN 'CONSUMPTION_REVERSAL' THEN quantity > 0 AND value >= 0
        ELSE quantity < 0 AND value <= 0 END)
);

CREATE INDEX consumption_record_store_day_item_ix
    ON reporting.consumption_record (store_id, business_date, sale_item_id);
CREATE INDEX consumption_record_line_ix ON reporting.consumption_record (line_id) WHERE line_id IS NOT NULL;

-- A wasted void's share of a sale's cost (D117 as amended): no stock moved; value (positive) moves from cost of
-- sales to waste. One row per (void, original sale movement), as inventory.waste_record.
CREATE TABLE reporting.consumption_waste_record
(
    void_id              UUID                     NOT NULL,
    original_movement_id UUID                     NOT NULL,
    store_id             UUID                     NOT NULL,
    business_date        DATE                     NOT NULL,
    sale_item_id         UUID,
    family_code          VARCHAR(50),
    line_id              UUID                     NOT NULL,
    warehouse_id         UUID                     NOT NULL,
    stock_item_id        UUID                     NOT NULL,
    quantity             NUMERIC(19, 6)           NOT NULL,
    value                NUMERIC(19, 4)           NOT NULL,
    projected_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT consumption_waste_record_pk PRIMARY KEY (void_id, original_movement_id),
    CONSTRAINT consumption_waste_record_amounts_ck CHECK (quantity > 0 AND value >= 0)
);

CREATE INDEX consumption_waste_record_store_day_item_ix
    ON reporting.consumption_waste_record (store_id, business_date, sale_item_id);
CREATE INDEX consumption_waste_record_line_ix ON reporting.consumption_waste_record (line_id);

-- D125's cost variances (cost of goods already consumed): a receipt cost correction's consumed share, or a linked
-- supplier return's (average - receipt cost) x quantity. Positive is more cost of goods, negative less.
CREATE TABLE reporting.cost_variance_record
(
    variance_id     UUID                     NOT NULL,
    source_type     VARCHAR(30)              NOT NULL,
    source_id       UUID                     NOT NULL,
    store_id        UUID                     NOT NULL,
    business_date   DATE                     NOT NULL,
    warehouse_id    UUID                     NOT NULL,
    stock_item_id   UUID                     NOT NULL,
    receipt_line_id UUID,
    value           NUMERIC(19, 4)           NOT NULL,
    projected_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT cost_variance_record_pk PRIMARY KEY (variance_id),
    CONSTRAINT cost_variance_record_source_type_ck CHECK (source_type IN ('COST_CORRECTION', 'SUPPLIER_RETURN')),
    CONSTRAINT cost_variance_record_value_ck CHECK (value <> 0)
);

CREATE INDEX cost_variance_record_store_day_ix ON reporting.cost_variance_record (store_id, business_date);

-- One row per discounted line of a discount source (D105): a round's promoted lines (BATCH) or one price adjustment
-- (ADJUSTMENT). discount_gross is negative for a REVERSAL.
CREATE TABLE reporting.discount_record
(
    source_type          VARCHAR(15)              NOT NULL,
    source_id            UUID                     NOT NULL,
    line_id              UUID                     NOT NULL,
    store_id             UUID                     NOT NULL,
    business_date        DATE                     NOT NULL,
    item_id              UUID,
    family_code          VARCHAR(50),
    origin               VARCHAR(15)              NOT NULL,
    kind                 VARCHAR(15)              NOT NULL,
    promotion_code       VARCHAR(50),
    operator_employee_id UUID,
    approver_employee_id UUID,
    quantity             NUMERIC(12, 3)           NOT NULL,
    discount_gross       NUMERIC(19, 4)           NOT NULL,
    projected_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT discount_record_pk PRIMARY KEY (source_type, source_id, line_id),
    CONSTRAINT discount_record_source_type_ck CHECK (source_type IN ('BATCH', 'ADJUSTMENT')),
    CONSTRAINT discount_record_origin_ck CHECK (origin IN ('PROMOTION', 'MANUAL', 'OFFER', 'LOYALTY')),
    CONSTRAINT discount_record_kind_ck CHECK (kind IN ('PROMOTION', 'DISCOUNT', 'REVERSAL', 'OFFER', 'REWARD')),
    CONSTRAINT discount_record_quantity_ck CHECK (quantity > 0),
    CONSTRAINT discount_record_sign_ck CHECK ((kind = 'REVERSAL') = (discount_gross < 0))
);

CREATE INDEX discount_record_store_day_origin_ix ON reporting.discount_record (store_id, business_date, origin);
