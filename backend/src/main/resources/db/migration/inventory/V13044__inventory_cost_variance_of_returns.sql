-- D125 amended: a return linked to a receipt line leaves at the average (D113) while the supplier credits the line's
-- cost; the difference, (receipt cost − average) × quantity, is a cost variance of that receipt line too. A variance
-- now comes from a cost correction or from a supplier return line, exactly one; receipt_line_id names the line.
-- DDL only: no existing variance row is touched (they are append-only).
ALTER TABLE inventory.cost_variance
    ALTER COLUMN correction_id DROP NOT NULL;
ALTER TABLE inventory.cost_variance
    ADD COLUMN return_line_id UUID,
    ADD COLUMN receipt_line_id UUID;
ALTER TABLE inventory.cost_variance
    ADD CONSTRAINT cost_variance_return_line_fk FOREIGN KEY (return_line_id)
        REFERENCES inventory.supplier_return_line (id),
    ADD CONSTRAINT cost_variance_receipt_line_fk FOREIGN KEY (receipt_line_id)
        REFERENCES inventory.goods_receipt_line (id),
    ADD CONSTRAINT cost_variance_return_line_uk UNIQUE (return_line_id),
    ADD CONSTRAINT cost_variance_source_ck CHECK (num_nonnulls(correction_id, return_line_id) = 1),
    ADD CONSTRAINT cost_variance_return_receipt_line_ck CHECK (return_line_id IS NULL OR receipt_line_id IS NOT NULL);
