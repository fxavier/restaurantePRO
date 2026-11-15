-- R6 S74a: production becomes tenant-isolated (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- Every production table is STORE class (RLS run) and gains the restrictive tenant policy on top: the store policy
-- stays. No UPDATE runs (production_ticket is append-only). destination_route and terminal_printer have no id: their
-- primary key leads with tenant_id.

-- (1)+(2) tenant_id on every table: a constant default first (catalog-only, no rewrite, no UPDATE), then the
-- database default, so an insert takes the bound tenant and an unbound one raises RS003.
ALTER TABLE production.printer
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE production.printer
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE production.destination_route
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE production.destination_route
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE production.terminal_printer
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE production.terminal_printer
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE production.production_ticket
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE production.production_ticket
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE production.print_job
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE production.print_job
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) Identity includes the tenant: a primary key that leads with tenant_id where the table has no id, and
-- UNIQUE (tenant_id, id) on every table with one (the target of composite foreign keys).
ALTER TABLE production.destination_route
    DROP CONSTRAINT destination_route_pk,
    ADD CONSTRAINT destination_route_pk PRIMARY KEY (tenant_id, store_id, destination_code);
ALTER TABLE production.terminal_printer
    DROP CONSTRAINT terminal_printer_pk,
    ADD CONSTRAINT terminal_printer_pk PRIMARY KEY (tenant_id, terminal_id);
ALTER TABLE production.printer
    ADD CONSTRAINT printer_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE production.production_ticket
    ADD CONSTRAINT production_ticket_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE production.print_job
    ADD CONSTRAINT print_job_tenant_id_uk UNIQUE (tenant_id, id);

-- (4) Business and reference keys per tenant: the (tenant_id, ...) key is created under a temporary name, the old
-- one dropped after the foreign keys that used it, and the old name restored (the application maps a violation
-- by constraint name, UniqueConstraints).
ALTER TABLE production.printer
    ADD CONSTRAINT tn_printer_id_store_uk UNIQUE (tenant_id, id, store_id);
ALTER TABLE production.printer
    ADD CONSTRAINT tn_printer_store_code_uk UNIQUE (tenant_id, store_id, code);
ALTER TABLE production.production_ticket
    ADD CONSTRAINT tn_production_ticket_source_uk UNIQUE (tenant_id, source_type, source_id, destination);
ALTER TABLE production.print_job
    ADD CONSTRAINT tn_print_job_dedupe_key_uk UNIQUE (tenant_id, dedupe_key);
CREATE UNIQUE INDEX tn_print_job_retry_of_uk ON production.print_job (tenant_id, retry_of) WHERE retry_of IS NOT NULL;

-- (5) In-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, then the
-- single-column one is dropped and the name kept.
ALTER TABLE production.printer
    ADD CONSTRAINT tn_printer_redirect_same_store_fk FOREIGN KEY (tenant_id, redirect_printer_id, store_id)
        REFERENCES production.printer (tenant_id, id, store_id) NOT VALID;
ALTER TABLE production.printer
    VALIDATE CONSTRAINT tn_printer_redirect_same_store_fk;
ALTER TABLE production.destination_route
    ADD CONSTRAINT tn_destination_route_printer_fk FOREIGN KEY (tenant_id, printer_id, store_id)
        REFERENCES production.printer (tenant_id, id, store_id) NOT VALID;
ALTER TABLE production.destination_route
    VALIDATE CONSTRAINT tn_destination_route_printer_fk;
ALTER TABLE production.terminal_printer
    ADD CONSTRAINT tn_terminal_printer_printer_fk FOREIGN KEY (tenant_id, printer_id, store_id)
        REFERENCES production.printer (tenant_id, id, store_id) NOT VALID;
ALTER TABLE production.terminal_printer
    VALIDATE CONSTRAINT tn_terminal_printer_printer_fk;
ALTER TABLE production.production_ticket
    ADD CONSTRAINT tn_production_ticket_printer_fk FOREIGN KEY (tenant_id, printer_id, store_id)
        REFERENCES production.printer (tenant_id, id, store_id) NOT VALID;
ALTER TABLE production.production_ticket
    VALIDATE CONSTRAINT tn_production_ticket_printer_fk;
ALTER TABLE production.print_job
    ADD CONSTRAINT tn_print_job_printer_fk FOREIGN KEY (tenant_id, printer_id, store_id)
        REFERENCES production.printer (tenant_id, id, store_id) NOT VALID;
ALTER TABLE production.print_job
    VALIDATE CONSTRAINT tn_print_job_printer_fk;
ALTER TABLE production.print_job
    ADD CONSTRAINT tn_print_job_retry_of_fk FOREIGN KEY (tenant_id, retry_of)
        REFERENCES production.print_job (tenant_id, id) NOT VALID;
ALTER TABLE production.print_job
    VALIDATE CONSTRAINT tn_print_job_retry_of_fk;
ALTER TABLE production.print_job
    ADD CONSTRAINT tn_print_job_ticket_fk FOREIGN KEY (tenant_id, ticket_id)
        REFERENCES production.production_ticket (tenant_id, id) NOT VALID;
ALTER TABLE production.print_job
    VALIDATE CONSTRAINT tn_print_job_ticket_fk;
ALTER TABLE production.printer
    DROP CONSTRAINT printer_redirect_same_store_fk;
ALTER TABLE production.destination_route
    DROP CONSTRAINT destination_route_printer_fk;
ALTER TABLE production.terminal_printer
    DROP CONSTRAINT terminal_printer_printer_fk;
ALTER TABLE production.production_ticket
    DROP CONSTRAINT production_ticket_printer_fk;
ALTER TABLE production.print_job
    DROP CONSTRAINT print_job_printer_fk;
ALTER TABLE production.print_job
    DROP CONSTRAINT print_job_retry_of_fk;
ALTER TABLE production.print_job
    DROP CONSTRAINT print_job_ticket_fk;
ALTER TABLE production.printer
    DROP CONSTRAINT printer_id_store_uk;
ALTER TABLE production.printer
    DROP CONSTRAINT printer_store_code_uk;
ALTER TABLE production.production_ticket
    DROP CONSTRAINT production_ticket_source_uk;
ALTER TABLE production.print_job
    DROP CONSTRAINT print_job_dedupe_key_uk;
DROP INDEX production.print_job_retry_of_uk;
ALTER TABLE production.printer
    RENAME CONSTRAINT tn_printer_id_store_uk TO printer_id_store_uk;
ALTER TABLE production.printer
    RENAME CONSTRAINT tn_printer_store_code_uk TO printer_store_code_uk;
ALTER TABLE production.production_ticket
    RENAME CONSTRAINT tn_production_ticket_source_uk TO production_ticket_source_uk;
ALTER TABLE production.print_job
    RENAME CONSTRAINT tn_print_job_dedupe_key_uk TO print_job_dedupe_key_uk;
ALTER TABLE production.printer
    RENAME CONSTRAINT tn_printer_redirect_same_store_fk TO printer_redirect_same_store_fk;
ALTER TABLE production.destination_route
    RENAME CONSTRAINT tn_destination_route_printer_fk TO destination_route_printer_fk;
ALTER TABLE production.terminal_printer
    RENAME CONSTRAINT tn_terminal_printer_printer_fk TO terminal_printer_printer_fk;
ALTER TABLE production.production_ticket
    RENAME CONSTRAINT tn_production_ticket_printer_fk TO production_ticket_printer_fk;
ALTER TABLE production.print_job
    RENAME CONSTRAINT tn_print_job_printer_fk TO print_job_printer_fk;
ALTER TABLE production.print_job
    RENAME CONSTRAINT tn_print_job_retry_of_fk TO print_job_retry_of_fk;
ALTER TABLE production.print_job
    RENAME CONSTRAINT tn_print_job_ticket_fk TO print_job_ticket_fk;
ALTER INDEX production.tn_print_job_retry_of_uk RENAME TO print_job_retry_of_uk;

-- (6) The restrictive tenant policy, the tenant_fixed trigger and both registries (the table keeps its store
-- or parent policy of the RLS run: the two are ANDed).
SELECT shared.rls_protect_tenant('production.printer');
SELECT shared.rls_protect_tenant('production.destination_route');
SELECT shared.rls_protect_tenant('production.terminal_printer');
SELECT shared.rls_protect_tenant('production.production_ticket');
SELECT shared.rls_protect_tenant('production.print_job');
