-- D101/D107 (S43): a discounted or offered line keeps the list unit price before the discount and the discount's
-- label, for printing and for a market adapter (B4). Its amounts stay the effective ones (unit_price, D59). Both
-- columns are added NULL, so no row is updated (an ISSUED document stays as issued, V10005); adding a column fires no
-- row trigger.
ALTER TABLE fiscal.fiscal_document_line
    ADD COLUMN list_unit_price NUMERIC(19, 4) NULL,
    ADD COLUMN discount_label  VARCHAR(80)    NULL,
    ADD CONSTRAINT fiscal_document_line_list_price_ck CHECK (list_unit_price IS NULL OR list_unit_price >= unit_price),
    ADD CONSTRAINT fiscal_document_line_discount_label_ck CHECK (discount_label IS NULL OR list_unit_price IS NOT NULL);
