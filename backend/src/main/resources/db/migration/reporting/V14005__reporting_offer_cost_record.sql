-- S53 review: the cost of offers is measured at close, from the order's billed lines (OrdersApi.offerFacts): one row
-- per CLOSED order's billing line priced as an OFFER through its lineage, at its net billed quantity (voids,
-- transfers out and splits out already left out; a reversed line is no longer an offer). Its cost is that quantity
-- times the unit cost of the round line it descends from (consumed_line_id: its sale consumption / its confirmed
-- quantity), read from consumption_record when the report is served. Keyed by (order, line), deleted only by a
-- rebuild of its store and range.
CREATE TABLE reporting.offer_cost_record
(
    order_id               UUID                     NOT NULL,
    line_id                UUID                     NOT NULL,
    store_id               UUID                     NOT NULL,
    business_date          DATE                     NOT NULL,
    item_id                UUID,
    family_code            VARCHAR(50),
    promotion_code         VARCHAR(50),
    operator_employee_id   UUID,
    approver_employee_id   UUID,
    net_quantity           NUMERIC(12, 3)           NOT NULL,
    consumed_line_id       UUID                     NOT NULL,
    consumed_line_quantity NUMERIC(12, 3)           NOT NULL,
    projected_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT offer_cost_record_pk PRIMARY KEY (order_id, line_id),
    CONSTRAINT offer_cost_record_quantities_ck CHECK (net_quantity > 0 AND consumed_line_quantity > 0)
);

CREATE INDEX offer_cost_record_store_day_ix ON reporting.offer_cost_record (store_id, business_date);

COMMENT ON COLUMN reporting.consumption_record.sale_item_id IS
    'The item sold on the round line the movement comes from (a menu for its components'' consumption), as orders '
    'confirmed it; the movement''s own item only when the round line is unknown. Null for a manual waste.';
COMMENT ON COLUMN reporting.consumption_waste_record.sale_item_id IS
    'The item sold on the round line the wasted share comes from (a menu for its components).';
COMMENT ON COLUMN reporting.discount_record.origin IS
    'PROMOTION, MANUAL, OFFER or LOYALTY; a REVERSAL takes the origin of what it takes back.';
