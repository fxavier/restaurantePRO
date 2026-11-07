-- S53 review: a discount on part of a line splits it first, and the split line has no consumption of its own. The
-- round line it descends from (DiscountFacts.DiscountLine.consumedLineId) and that line's confirmed quantity let an
-- offer's cost be its share of the round line's consumption. Projections only: rows written before are rebuilt.
ALTER TABLE reporting.discount_record
    ADD COLUMN consumed_line_id       UUID,
    ADD COLUMN consumed_line_quantity NUMERIC(12, 3),
    ADD CONSTRAINT discount_record_consumed_line_ck CHECK ((consumed_line_id IS NULL) = (consumed_line_quantity IS NULL)
        AND (consumed_line_quantity IS NULL OR consumed_line_quantity > 0));
