-- Document lines, copied from the confirmed order-line snapshots (spec §8.4, D59). The catalog is never read again.
CREATE TABLE fiscal.fiscal_document_line
(
    id             UUID           NOT NULL,
    document_id    UUID           NOT NULL,
    line_no        INTEGER        NOT NULL,
    source_line_id UUID,
    description    VARCHAR(200)   NOT NULL,
    quantity       NUMERIC(12, 3) NOT NULL,
    unit_price     NUMERIC(19, 4) NOT NULL,
    tax_rate       NUMERIC(7, 4)  NOT NULL,
    gross          NUMERIC(19, 4) NOT NULL,
    net            NUMERIC(19, 4) NOT NULL,
    tax            NUMERIC(19, 4) NOT NULL,
    CONSTRAINT fiscal_document_line_pk PRIMARY KEY (id),
    CONSTRAINT fiscal_document_line_document_fk FOREIGN KEY (document_id) REFERENCES fiscal.fiscal_document (id),
    CONSTRAINT fiscal_document_line_no_uk UNIQUE (document_id, line_no),
    CONSTRAINT fiscal_document_line_no_ck CHECK (line_no >= 1),
    CONSTRAINT fiscal_document_line_description_ck CHECK (btrim(description) <> ''),
    CONSTRAINT fiscal_document_line_quantity_ck CHECK (quantity > 0),
    CONSTRAINT fiscal_document_line_tax_rate_ck CHECK (tax_rate >= 0),
    CONSTRAINT fiscal_document_line_amounts_ck CHECK (gross = net + tax)
);
