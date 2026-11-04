-- Tax summary per rate of a document: the sum of its lines' net and tax (D59, no re-rounding of totals).
CREATE TABLE fiscal.fiscal_document_tax
(
    id          UUID           NOT NULL,
    document_id UUID           NOT NULL,
    tax_rate    NUMERIC(7, 4)  NOT NULL,
    net         NUMERIC(19, 4) NOT NULL,
    tax         NUMERIC(19, 4) NOT NULL,
    CONSTRAINT fiscal_document_tax_pk PRIMARY KEY (id),
    CONSTRAINT fiscal_document_tax_document_fk FOREIGN KEY (document_id) REFERENCES fiscal.fiscal_document (id),
    CONSTRAINT fiscal_document_tax_rate_uk UNIQUE (document_id, tax_rate),
    CONSTRAINT fiscal_document_tax_rate_ck CHECK (tax_rate >= 0)
);
