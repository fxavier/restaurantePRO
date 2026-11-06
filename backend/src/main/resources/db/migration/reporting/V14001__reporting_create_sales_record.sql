-- Reporting projections of sales (spec §5 "Relatórios", §8.1 SalesRecord, §8.6; D84, D85). A projection, never a
-- fact: one row per ISSUED fiscal document, written once from FiscalApi.documentFacts (INSERT ... ON CONFLICT DO
-- NOTHING keyed by the document) and deleted only by a rebuild of its store and range. Ids of other modules are
-- plain columns (D6). business_date is the order's business day; local_date and local_hour are the store's wall
-- clock at the document's issue, computed once (a later timezone change does not rewrite history).
CREATE SCHEMA IF NOT EXISTS reporting;

CREATE TABLE reporting.sales_record
(
    document_id           UUID                     NOT NULL,
    store_id              UUID                     NOT NULL,
    business_date         DATE                     NOT NULL,
    local_date            DATE                     NOT NULL,
    local_hour            SMALLINT                 NOT NULL,
    issued_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    order_id              UUID,
    partition_id          UUID,
    terminal_id           UUID,
    issued_by_employee_id UUID,
    customer_id           UUID,
    on_account            BOOLEAN                  NOT NULL,
    series_code           VARCHAR(20)              NOT NULL,
    number                BIGINT                   NOT NULL,
    non_fiscal            BOOLEAN                  NOT NULL,
    currency              CHAR(3)                  NOT NULL,
    total_gross           NUMERIC(19, 4)           NOT NULL,
    total_net             NUMERIC(19, 4)           NOT NULL,
    total_tax             NUMERIC(19, 4)           NOT NULL,
    projected_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT sales_record_pk PRIMARY KEY (document_id),
    CONSTRAINT sales_record_local_hour_ck CHECK (local_hour BETWEEN 0 AND 23),
    CONSTRAINT sales_record_totals_ck CHECK (total_gross = total_net + total_tax),
    CONSTRAINT sales_record_on_account_ck CHECK (NOT on_account OR customer_id IS NOT NULL)
);

CREATE INDEX sales_record_store_day_ix ON reporting.sales_record (store_id, business_date);

-- The document's lines with the dimensions resolved at projection time (D84): the item and its family (from the
-- catalog release the line was priced from), the serving employee, the table's area and the order's mode.
CREATE TABLE reporting.sales_record_line
(
    document_id           UUID                     NOT NULL,
    line_no               INTEGER                  NOT NULL,
    store_id              UUID                     NOT NULL,
    business_date         DATE                     NOT NULL,
    local_hour            SMALLINT                 NOT NULL,
    item_id               UUID,
    item_code             VARCHAR(50),
    family_code           VARCHAR(50),
    description           VARCHAR(200)             NOT NULL,
    quantity              NUMERIC(12, 3)           NOT NULL,
    gross                 NUMERIC(19, 4)           NOT NULL,
    net                   NUMERIC(19, 4)           NOT NULL,
    tax                   NUMERIC(19, 4)           NOT NULL,
    tax_code              VARCHAR(50),
    tax_rate              NUMERIC(7, 4)            NOT NULL,
    served_by_employee_id UUID,
    area_id               UUID,
    area_code             VARCHAR(50),
    mode                  VARCHAR(10),
    projected_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT sales_record_line_pk PRIMARY KEY (document_id, line_no),
    CONSTRAINT sales_record_line_record_fk FOREIGN KEY (document_id) REFERENCES reporting.sales_record (document_id)
        ON DELETE CASCADE,
    CONSTRAINT sales_record_line_local_hour_ck CHECK (local_hour BETWEEN 0 AND 23),
    CONSTRAINT sales_record_line_quantity_ck CHECK (quantity > 0),
    CONSTRAINT sales_record_line_amounts_ck CHECK (gross = net + tax),
    CONSTRAINT sales_record_line_mode_ck CHECK (mode IN ('DINE_IN', 'COUNTER', 'TAKEAWAY'))
);

CREATE INDEX sales_record_line_store_day_ix ON reporting.sales_record_line (store_id, business_date);
CREATE INDEX sales_record_line_store_day_item_ix ON reporting.sales_record_line (store_id, business_date, item_code);
CREATE INDEX sales_record_line_store_day_served_by_ix
    ON reporting.sales_record_line (store_id, business_date, served_by_employee_id);

-- The document's tax summary per rate, as fiscal issued it: authoritative (S28/D68 amended: never recomputed from
-- the order). tax_code names the catalog tax code(s) of the document's lines at that rate.
CREATE TABLE reporting.sales_record_tax
(
    document_id   UUID                     NOT NULL,
    tax_code      VARCHAR(120)             NOT NULL,
    tax_rate      NUMERIC(7, 4)            NOT NULL,
    store_id      UUID                     NOT NULL,
    business_date DATE                     NOT NULL,
    net           NUMERIC(19, 4)           NOT NULL,
    tax           NUMERIC(19, 4)           NOT NULL,
    projected_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT sales_record_tax_pk PRIMARY KEY (document_id, tax_code, tax_rate),
    CONSTRAINT sales_record_tax_record_fk FOREIGN KEY (document_id) REFERENCES reporting.sales_record (document_id)
        ON DELETE CASCADE
);

CREATE INDEX sales_record_tax_store_day_ix ON reporting.sales_record_tax (store_id, business_date);
