-- R6 S74b: fiscal becomes tenant-isolated (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- The 2 STORE tables and 4 CHILD tables keep their V10012 store / parent policies and gain the restrictive tenant
-- policy. Series and document-number uniqueness become per tenant: the same series code in two tenants issues
-- independent numbers. No UPDATE runs: every new column takes a constant default (catalog-only, no rewrite), then the
-- database default shared.rls_tenant_id(). The issued-document immutability guards (V10005, V10009), the series guard
-- (V10001) and the R3 lock order (document, then series) are untouched: no row is rewritten.

-- (1)+(2) tenant_id on every table.
ALTER TABLE fiscal.fiscal_series
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE fiscal.fiscal_series
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE fiscal.fiscal_document
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE fiscal.fiscal_document
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE fiscal.fiscal_document_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE fiscal.fiscal_document_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE fiscal.fiscal_document_tax
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE fiscal.fiscal_document_tax
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE fiscal.fiscal_document_payment
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE fiscal.fiscal_document_payment
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE fiscal.fiscal_document_print
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE fiscal.fiscal_document_print
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) UNIQUE (tenant_id, id). fiscal_document_payment has no id: its primary key (document_id, position) leads with
-- the tenant instead.
ALTER TABLE fiscal.fiscal_series
    ADD CONSTRAINT fiscal_series_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE fiscal.fiscal_document
    ADD CONSTRAINT fiscal_document_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE fiscal.fiscal_document_line
    ADD CONSTRAINT fiscal_document_line_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE fiscal.fiscal_document_tax
    ADD CONSTRAINT fiscal_document_tax_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE fiscal.fiscal_document_print
    ADD CONSTRAINT fiscal_document_print_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE fiscal.fiscal_document_payment
    DROP CONSTRAINT fiscal_document_payment_pk,
    ADD CONSTRAINT fiscal_document_payment_pk PRIMARY KEY (tenant_id, document_id, position);

-- (4) Business unique keys per tenant: create the (tenant_id, ...) one, drop the old, keep the old name (the
-- application maps a violation by constraint name, UniqueConstraints).
ALTER TABLE fiscal.fiscal_series
    ADD CONSTRAINT fiscal_series_code_uk_t UNIQUE (tenant_id, store_id, document_type, code);
ALTER TABLE fiscal.fiscal_series
    DROP CONSTRAINT fiscal_series_code_uk;
ALTER TABLE fiscal.fiscal_series
    RENAME CONSTRAINT fiscal_series_code_uk_t TO fiscal_series_code_uk;
CREATE UNIQUE INDEX fiscal_series_active_uk_t ON fiscal.fiscal_series (tenant_id, store_id, document_type)
    WHERE active;
DROP INDEX fiscal.fiscal_series_active_uk;
ALTER INDEX fiscal.fiscal_series_active_uk_t RENAME TO fiscal_series_active_uk;
ALTER TABLE fiscal.fiscal_document
    ADD CONSTRAINT fiscal_document_number_uk_t UNIQUE (tenant_id, series_id, number);
ALTER TABLE fiscal.fiscal_document
    DROP CONSTRAINT fiscal_document_number_uk;
ALTER TABLE fiscal.fiscal_document
    RENAME CONSTRAINT fiscal_document_number_uk_t TO fiscal_document_number_uk;
CREATE UNIQUE INDEX fiscal_document_source_uk_t ON fiscal.fiscal_document (tenant_id, source_type, source_id, document_type)
    WHERE status = 'DRAFT' OR status = 'ISSUED';
DROP INDEX fiscal.fiscal_document_source_uk;
ALTER INDEX fiscal.fiscal_document_source_uk_t RENAME TO fiscal_document_source_uk;
ALTER TABLE fiscal.fiscal_document_line
    ADD CONSTRAINT fiscal_document_line_no_uk_t UNIQUE (tenant_id, document_id, line_no);
ALTER TABLE fiscal.fiscal_document_line
    DROP CONSTRAINT fiscal_document_line_no_uk;
ALTER TABLE fiscal.fiscal_document_line
    RENAME CONSTRAINT fiscal_document_line_no_uk_t TO fiscal_document_line_no_uk;
ALTER TABLE fiscal.fiscal_document_tax
    ADD CONSTRAINT fiscal_document_tax_rate_uk_t UNIQUE (tenant_id, document_id, tax_rate);
ALTER TABLE fiscal.fiscal_document_tax
    DROP CONSTRAINT fiscal_document_tax_rate_uk;
ALTER TABLE fiscal.fiscal_document_tax
    RENAME CONSTRAINT fiscal_document_tax_rate_uk_t TO fiscal_document_tax_rate_uk;
ALTER TABLE fiscal.fiscal_document_print
    ADD CONSTRAINT fiscal_document_print_copy_uk_t UNIQUE (tenant_id, document_id, copy_no);
ALTER TABLE fiscal.fiscal_document_print
    DROP CONSTRAINT fiscal_document_print_copy_uk;
ALTER TABLE fiscal.fiscal_document_print
    RENAME CONSTRAINT fiscal_document_print_copy_uk_t TO fiscal_document_print_copy_uk;

-- (5) The in-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, then drop the
-- single-column one and keep its name. series_id is nullable on a DRAFT and then unchecked.
ALTER TABLE fiscal.fiscal_document
    ADD CONSTRAINT fiscal_document_series_fk_t FOREIGN KEY (tenant_id, series_id)
        REFERENCES fiscal.fiscal_series (tenant_id, id) NOT VALID;
ALTER TABLE fiscal.fiscal_document
    VALIDATE CONSTRAINT fiscal_document_series_fk_t;
ALTER TABLE fiscal.fiscal_document
    DROP CONSTRAINT fiscal_document_series_fk;
ALTER TABLE fiscal.fiscal_document
    RENAME CONSTRAINT fiscal_document_series_fk_t TO fiscal_document_series_fk;
ALTER TABLE fiscal.fiscal_document_line
    ADD CONSTRAINT fiscal_document_line_document_fk_t FOREIGN KEY (tenant_id, document_id)
        REFERENCES fiscal.fiscal_document (tenant_id, id) NOT VALID;
ALTER TABLE fiscal.fiscal_document_line
    VALIDATE CONSTRAINT fiscal_document_line_document_fk_t;
ALTER TABLE fiscal.fiscal_document_line
    DROP CONSTRAINT fiscal_document_line_document_fk;
ALTER TABLE fiscal.fiscal_document_line
    RENAME CONSTRAINT fiscal_document_line_document_fk_t TO fiscal_document_line_document_fk;
ALTER TABLE fiscal.fiscal_document_tax
    ADD CONSTRAINT fiscal_document_tax_document_fk_t FOREIGN KEY (tenant_id, document_id)
        REFERENCES fiscal.fiscal_document (tenant_id, id) NOT VALID;
ALTER TABLE fiscal.fiscal_document_tax
    VALIDATE CONSTRAINT fiscal_document_tax_document_fk_t;
ALTER TABLE fiscal.fiscal_document_tax
    DROP CONSTRAINT fiscal_document_tax_document_fk;
ALTER TABLE fiscal.fiscal_document_tax
    RENAME CONSTRAINT fiscal_document_tax_document_fk_t TO fiscal_document_tax_document_fk;
ALTER TABLE fiscal.fiscal_document_payment
    ADD CONSTRAINT fiscal_document_payment_document_fk_t FOREIGN KEY (tenant_id, document_id)
        REFERENCES fiscal.fiscal_document (tenant_id, id) NOT VALID;
ALTER TABLE fiscal.fiscal_document_payment
    VALIDATE CONSTRAINT fiscal_document_payment_document_fk_t;
ALTER TABLE fiscal.fiscal_document_payment
    DROP CONSTRAINT fiscal_document_payment_document_fk;
ALTER TABLE fiscal.fiscal_document_payment
    RENAME CONSTRAINT fiscal_document_payment_document_fk_t TO fiscal_document_payment_document_fk;
ALTER TABLE fiscal.fiscal_document_print
    ADD CONSTRAINT fiscal_document_print_document_fk_t FOREIGN KEY (tenant_id, document_id)
        REFERENCES fiscal.fiscal_document (tenant_id, id) NOT VALID;
ALTER TABLE fiscal.fiscal_document_print
    VALIDATE CONSTRAINT fiscal_document_print_document_fk_t;
ALTER TABLE fiscal.fiscal_document_print
    DROP CONSTRAINT fiscal_document_print_document_fk;
ALTER TABLE fiscal.fiscal_document_print
    RENAME CONSTRAINT fiscal_document_print_document_fk_t TO fiscal_document_print_document_fk;

-- (6) The tenant policy through the helper, then the registries; the V10012 store / parent policies stay.
SELECT shared.rls_protect_tenant('fiscal.fiscal_series');
SELECT shared.rls_protect_tenant('fiscal.fiscal_document');
SELECT shared.rls_protect_tenant('fiscal.fiscal_document_line');
SELECT shared.rls_protect_tenant('fiscal.fiscal_document_tax');
SELECT shared.rls_protect_tenant('fiscal.fiscal_document_payment');
SELECT shared.rls_protect_tenant('fiscal.fiscal_document_print');
