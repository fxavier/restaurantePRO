-- D127, D131: a DELIVERY order (S50) is an order mode like any other; its document lines carry it into the projection.
ALTER TABLE reporting.sales_record_line
    DROP CONSTRAINT sales_record_line_mode_ck;
ALTER TABLE reporting.sales_record_line
    ADD CONSTRAINT sales_record_line_mode_ck CHECK (mode IN ('DINE_IN', 'COUNTER', 'TAKEAWAY', 'DELIVERY'));
