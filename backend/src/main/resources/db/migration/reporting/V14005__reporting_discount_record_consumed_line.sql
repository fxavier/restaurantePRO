-- S53 review: a discount on part of a line splits it first, and the split line has no consumption of its own. The
-- round line it descends from (DiscountFacts.DiscountLine.consumedLineId) and that line's confirmed quantity let an
-- offer's cost be its share of the round line's consumption. Projections only: rows written before are rebuilt.
ALTER TABLE reporting.discount_record
    ADD COLUMN consumed_line_id       UUID,
    ADD COLUMN consumed_line_quantity NUMERIC(12, 3),
    ADD CONSTRAINT discount_record_consumed_line_ck CHECK ((consumed_line_id IS NULL) = (consumed_line_quantity IS NULL)
        AND (consumed_line_quantity IS NULL OR consumed_line_quantity > 0));

-- S53 review: a REVERSAL nets what it takes back, and units of an offered line voided after the offer carry no offer
-- cost. at is the discount's instant; void_record gains the voided line and its instant (null on rows projected
-- before this; a rebuild fills them).
ALTER TABLE reporting.discount_record
    ADD COLUMN at TIMESTAMP WITH TIME ZONE NOT NULL;
ALTER TABLE reporting.void_record
    ADD COLUMN line_id   UUID,
    ADD COLUMN voided_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX void_record_line_ix ON reporting.void_record (line_id) WHERE line_id IS NOT NULL;

COMMENT ON COLUMN reporting.consumption_record.sale_item_id IS
    'The item sold on the round line the movement comes from (a menu for its components'' consumption), as orders '
    'confirmed it; the movement''s own item only when the round line is unknown. Null for a manual waste.';
COMMENT ON COLUMN reporting.consumption_waste_record.sale_item_id IS
    'The item sold on the round line the wasted share comes from (a menu for its components).';
COMMENT ON COLUMN reporting.discount_record.origin IS
    'PROMOTION, MANUAL, OFFER or LOYALTY; a REVERSAL takes the origin of what it takes back.';
