-- Fiscal documents (spec §8.3: DRAFT -> ISSUED, P8 one SALE per order). Snapshots of company, store and buyer
-- (P17, P21); amounts NUMERIC(19,4) in the company currency (D59). Orders and stores are referenced by id only (D6).
CREATE TABLE fiscal.fiscal_document
(
    id                    UUID                     NOT NULL,
    store_id              UUID                     NOT NULL,
    document_type         VARCHAR(20)              NOT NULL,
    status                VARCHAR(10)              NOT NULL,
    source_type           VARCHAR(20)              NOT NULL,
    source_id             UUID                     NOT NULL,
    series_id             UUID,
    series_code           VARCHAR(20),
    number                BIGINT,
    currency              VARCHAR(3)               NOT NULL,
    total_gross           NUMERIC(19, 4)           NOT NULL,
    total_net             NUMERIC(19, 4)           NOT NULL,
    total_tax             NUMERIC(19, 4)           NOT NULL,
    company_name          VARCHAR(200)             NOT NULL,
    company_tax_id        VARCHAR(50)              NOT NULL,
    company_country       VARCHAR(2)               NOT NULL,
    store_code            VARCHAR(20)              NOT NULL,
    store_name            VARCHAR(120)             NOT NULL,
    store_timezone        VARCHAR(64)              NOT NULL,
    buyer_name            VARCHAR(200),
    buyer_tax_id          VARCHAR(50),
    buyer_address         VARCHAR(300),
    adapter_code          VARCHAR(40),
    adapter_data          TEXT,
    qr_payload            TEXT,
    non_fiscal            BOOLEAN,
    issued_at             TIMESTAMP WITH TIME ZONE,
    issued_by_employee_id UUID,
    issued_terminal_id    UUID,
    last_error            VARCHAR(1000),
    last_error_at         TIMESTAMP WITH TIME ZONE,
    version               BIGINT                   NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fiscal_document_pk PRIMARY KEY (id),
    CONSTRAINT fiscal_document_series_fk FOREIGN KEY (series_id) REFERENCES fiscal.fiscal_series (id),
    -- the gapless-numbering backstop next to the series lock (D50 §7.6)
    CONSTRAINT fiscal_document_number_uk UNIQUE (series_id, number),
    CONSTRAINT fiscal_document_document_type_ck CHECK (document_type IN ('SALE')),
    CONSTRAINT fiscal_document_status_ck CHECK (status IN ('DRAFT', 'ISSUED')),
    CONSTRAINT fiscal_document_source_type_ck CHECK (source_type IN ('ORDER')),
    CONSTRAINT fiscal_document_currency_ck CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT fiscal_document_totals_ck CHECK (total_gross = total_net + total_tax),
    -- ISSUED => numbered and stamped; a DRAFT never holds a number, so a failed issue cannot leave a gap
    CONSTRAINT fiscal_document_issued_ck CHECK (status <> 'ISSUED' OR (series_id IS NOT NULL AND series_code IS NOT NULL
        AND number IS NOT NULL AND issued_at IS NOT NULL AND adapter_code IS NOT NULL AND non_fiscal IS NOT NULL)),
    CONSTRAINT fiscal_document_draft_ck CHECK (status <> 'DRAFT' OR (series_id IS NULL AND number IS NULL
        AND issued_at IS NULL))
);

-- One SALE per order (P8): createSaleDraft is idempotent per source, and two closes of one order collide here.
CREATE UNIQUE INDEX fiscal_document_source_uk ON fiscal.fiscal_document (source_type, source_id, document_type)
    WHERE status IN ('DRAFT', 'ISSUED');
CREATE INDEX fiscal_document_store_issued_ix ON fiscal.fiscal_document (store_id, issued_at);
CREATE INDEX fiscal_document_store_status_ix ON fiscal.fiscal_document (store_id, status);
