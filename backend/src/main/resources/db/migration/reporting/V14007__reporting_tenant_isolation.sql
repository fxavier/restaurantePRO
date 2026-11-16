-- R6 S74d: every projection row and rebuild run belongs to one tenant; the store policies stay and the tenant one is added (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- No UPDATE runs: every new column takes a constant default (catalog-only, no rewrite), then the database default
-- shared.rls_tenant_id(), so an insert takes the bound tenant and an unbound one raises RS003. The bootstrap tenant
-- (P70 = none in production) is the fixed literal of every R6 migration and of TenantTestSupport.BOOTSTRAP.
-- Generated from the schema this module's earlier migrations built; every constraint keeps its name, so the
-- application's mapping of a violation by constraint name (UniqueConstraints) is unchanged.

-- (1)+(2) tenant_id on every table.
ALTER TABLE reporting.consumption_record
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.consumption_record
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.consumption_waste_record
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.consumption_waste_record
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.cost_variance_record
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.cost_variance_record
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.credit_record
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.credit_record
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.discount_record
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.discount_record
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.offer_cost_record
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.offer_cost_record
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.payment_record
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.payment_record
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.rebuild_run
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.rebuild_run
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.sales_record
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.sales_record
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.sales_record_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.sales_record_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.sales_record_tax
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.sales_record_tax
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE reporting.void_record
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE reporting.void_record
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) UNIQUE (tenant_id, id) on every table with an id: the target of composite foreign keys.
ALTER TABLE reporting.rebuild_run
    ADD CONSTRAINT rebuild_run_tenant_id_uk UNIQUE (tenant_id, id);

-- (4) Business unique keys per tenant: create the (tenant_id, ...) one, then (after the foreign keys moved) drop
-- the old and keep its name.
CREATE UNIQUE INDEX rebuild_run_running_uk_tenant ON reporting.rebuild_run USING btree (tenant_id, store_id) WHERE ((status)::text = 'RUNNING'::text);
CREATE UNIQUE INDEX sales_record_tenant_pk_ix ON reporting.sales_record (tenant_id, document_id);

-- (5) In-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, drop the
-- single-column one, keep its name.
ALTER TABLE reporting.sales_record_line
    ADD CONSTRAINT sales_record_line_record_fk_tenant FOREIGN KEY (tenant_id, document_id)
        REFERENCES reporting.sales_record (tenant_id, document_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE reporting.sales_record_line
    VALIDATE CONSTRAINT sales_record_line_record_fk_tenant;
ALTER TABLE reporting.sales_record_tax
    ADD CONSTRAINT sales_record_tax_record_fk_tenant FOREIGN KEY (tenant_id, document_id)
        REFERENCES reporting.sales_record (tenant_id, document_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE reporting.sales_record_tax
    VALIDATE CONSTRAINT sales_record_tax_record_fk_tenant;
ALTER TABLE reporting.sales_record_line
    DROP CONSTRAINT sales_record_line_record_fk;
ALTER TABLE reporting.sales_record_tax
    DROP CONSTRAINT sales_record_tax_record_fk;
ALTER TABLE reporting.sales_record_line
    RENAME CONSTRAINT sales_record_line_record_fk_tenant TO sales_record_line_record_fk;
ALTER TABLE reporting.sales_record_tax
    RENAME CONSTRAINT sales_record_tax_record_fk_tenant TO sales_record_tax_record_fk;

-- Old unique keys out (nothing references them any more), the new ones take their names.
DROP INDEX reporting.rebuild_run_running_uk;
ALTER INDEX reporting.rebuild_run_running_uk_tenant RENAME TO rebuild_run_running_uk;

-- (3b) A table with no id keeps its identity in a primary key that leads with tenant_id (spec: the policy helper
-- asserts it).
ALTER TABLE reporting.consumption_record
    DROP CONSTRAINT consumption_record_pk;
ALTER TABLE reporting.consumption_record
    ADD CONSTRAINT consumption_record_pk PRIMARY KEY (tenant_id, movement_id);
ALTER TABLE reporting.consumption_waste_record
    DROP CONSTRAINT consumption_waste_record_pk;
ALTER TABLE reporting.consumption_waste_record
    ADD CONSTRAINT consumption_waste_record_pk PRIMARY KEY (tenant_id, void_id, original_movement_id);
ALTER TABLE reporting.cost_variance_record
    DROP CONSTRAINT cost_variance_record_pk;
ALTER TABLE reporting.cost_variance_record
    ADD CONSTRAINT cost_variance_record_pk PRIMARY KEY (tenant_id, variance_id);
ALTER TABLE reporting.credit_record
    DROP CONSTRAINT credit_record_pk;
ALTER TABLE reporting.credit_record
    ADD CONSTRAINT credit_record_pk PRIMARY KEY (tenant_id, charge_id);
ALTER TABLE reporting.discount_record
    DROP CONSTRAINT discount_record_pk;
ALTER TABLE reporting.discount_record
    ADD CONSTRAINT discount_record_pk PRIMARY KEY (tenant_id, source_type, source_id, line_id);
ALTER TABLE reporting.offer_cost_record
    DROP CONSTRAINT offer_cost_record_pk;
ALTER TABLE reporting.offer_cost_record
    ADD CONSTRAINT offer_cost_record_pk PRIMARY KEY (tenant_id, order_id, line_id);
ALTER TABLE reporting.payment_record
    DROP CONSTRAINT payment_record_pk;
ALTER TABLE reporting.payment_record
    ADD CONSTRAINT payment_record_pk PRIMARY KEY (tenant_id, source_type, source_id);
ALTER TABLE reporting.sales_record
    DROP CONSTRAINT sales_record_pk;
ALTER TABLE reporting.sales_record
    ADD CONSTRAINT sales_record_pk PRIMARY KEY USING INDEX sales_record_tenant_pk_ix;
ALTER TABLE reporting.sales_record_line
    DROP CONSTRAINT sales_record_line_pk;
ALTER TABLE reporting.sales_record_line
    ADD CONSTRAINT sales_record_line_pk PRIMARY KEY (tenant_id, document_id, line_no);
ALTER TABLE reporting.sales_record_tax
    DROP CONSTRAINT sales_record_tax_pk;
ALTER TABLE reporting.sales_record_tax
    ADD CONSTRAINT sales_record_tax_pk PRIMARY KEY (tenant_id, document_id, tax_code, tax_rate);
ALTER TABLE reporting.void_record
    DROP CONSTRAINT void_record_pk;
ALTER TABLE reporting.void_record
    ADD CONSTRAINT void_record_pk PRIMARY KEY (tenant_id, void_id);

-- (6) The tenant policy through the helper, then the registries (out of UNSCOPED).
SELECT shared.rls_protect_tenant('reporting.consumption_record');
SELECT shared.rls_protect_tenant('reporting.consumption_waste_record');
SELECT shared.rls_protect_tenant('reporting.cost_variance_record');
SELECT shared.rls_protect_tenant('reporting.credit_record');
SELECT shared.rls_protect_tenant('reporting.discount_record');
SELECT shared.rls_protect_tenant('reporting.offer_cost_record');
SELECT shared.rls_protect_tenant('reporting.payment_record');
SELECT shared.rls_protect_tenant('reporting.rebuild_run');
SELECT shared.rls_protect_tenant('reporting.sales_record');
SELECT shared.rls_protect_tenant('reporting.sales_record_line');
SELECT shared.rls_protect_tenant('reporting.sales_record_tax');
SELECT shared.rls_protect_tenant('reporting.void_record');

-- D180 (indexes): tenant_id leads every composite index and every index on store_id, so the restrictive tenant
-- policy is served by the index. Same names, same partial predicates; built under a temporary name, then swapped.
CREATE INDEX consumption_record_store_day_item_ix_tenant ON reporting.consumption_record USING btree (tenant_id, store_id, business_date, sale_item_id);
DROP INDEX reporting.consumption_record_store_day_item_ix;
ALTER INDEX reporting.consumption_record_store_day_item_ix_tenant RENAME TO consumption_record_store_day_item_ix;
CREATE INDEX consumption_waste_record_store_day_item_ix_tenant ON reporting.consumption_waste_record USING btree (tenant_id, store_id, business_date, sale_item_id);
DROP INDEX reporting.consumption_waste_record_store_day_item_ix;
ALTER INDEX reporting.consumption_waste_record_store_day_item_ix_tenant RENAME TO consumption_waste_record_store_day_item_ix;
CREATE INDEX cost_variance_record_store_day_ix_tenant ON reporting.cost_variance_record USING btree (tenant_id, store_id, business_date);
DROP INDEX reporting.cost_variance_record_store_day_ix;
ALTER INDEX reporting.cost_variance_record_store_day_ix_tenant RENAME TO cost_variance_record_store_day_ix;
CREATE INDEX credit_record_store_day_ix_tenant ON reporting.credit_record USING btree (tenant_id, store_id, business_date);
DROP INDEX reporting.credit_record_store_day_ix;
ALTER INDEX reporting.credit_record_store_day_ix_tenant RENAME TO credit_record_store_day_ix;
CREATE INDEX discount_record_store_day_origin_ix_tenant ON reporting.discount_record USING btree (tenant_id, store_id, business_date, origin);
DROP INDEX reporting.discount_record_store_day_origin_ix;
ALTER INDEX reporting.discount_record_store_day_origin_ix_tenant RENAME TO discount_record_store_day_origin_ix;
CREATE INDEX offer_cost_record_store_day_ix_tenant ON reporting.offer_cost_record USING btree (tenant_id, store_id, business_date);
DROP INDEX reporting.offer_cost_record_store_day_ix;
ALTER INDEX reporting.offer_cost_record_store_day_ix_tenant RENAME TO offer_cost_record_store_day_ix;
CREATE INDEX payment_record_store_day_ix_tenant ON reporting.payment_record USING btree (tenant_id, store_id, business_date);
DROP INDEX reporting.payment_record_store_day_ix;
ALTER INDEX reporting.payment_record_store_day_ix_tenant RENAME TO payment_record_store_day_ix;
CREATE INDEX payment_record_store_day_method_ix_tenant ON reporting.payment_record USING btree (tenant_id, store_id, business_date, method_code);
DROP INDEX reporting.payment_record_store_day_method_ix;
ALTER INDEX reporting.payment_record_store_day_method_ix_tenant RENAME TO payment_record_store_day_method_ix;
CREATE INDEX rebuild_run_store_started_ix_tenant ON reporting.rebuild_run USING btree (tenant_id, store_id, started_at);
DROP INDEX reporting.rebuild_run_store_started_ix;
ALTER INDEX reporting.rebuild_run_store_started_ix_tenant RENAME TO rebuild_run_store_started_ix;
CREATE INDEX sales_record_line_store_day_item_ix_tenant ON reporting.sales_record_line USING btree (tenant_id, store_id, business_date, item_code);
DROP INDEX reporting.sales_record_line_store_day_item_ix;
ALTER INDEX reporting.sales_record_line_store_day_item_ix_tenant RENAME TO sales_record_line_store_day_item_ix;
CREATE INDEX sales_record_line_store_day_ix_tenant ON reporting.sales_record_line USING btree (tenant_id, store_id, business_date);
DROP INDEX reporting.sales_record_line_store_day_ix;
ALTER INDEX reporting.sales_record_line_store_day_ix_tenant RENAME TO sales_record_line_store_day_ix;
CREATE INDEX sales_record_line_store_day_served_by_ix_tenant ON reporting.sales_record_line USING btree (tenant_id, store_id, business_date, served_by_employee_id);
DROP INDEX reporting.sales_record_line_store_day_served_by_ix;
ALTER INDEX reporting.sales_record_line_store_day_served_by_ix_tenant RENAME TO sales_record_line_store_day_served_by_ix;
CREATE INDEX sales_record_store_day_ix_tenant ON reporting.sales_record USING btree (tenant_id, store_id, business_date);
DROP INDEX reporting.sales_record_store_day_ix;
ALTER INDEX reporting.sales_record_store_day_ix_tenant RENAME TO sales_record_store_day_ix;
CREATE INDEX sales_record_tax_store_day_ix_tenant ON reporting.sales_record_tax USING btree (tenant_id, store_id, business_date);
DROP INDEX reporting.sales_record_tax_store_day_ix;
ALTER INDEX reporting.sales_record_tax_store_day_ix_tenant RENAME TO sales_record_tax_store_day_ix;
CREATE INDEX void_record_store_day_ix_tenant ON reporting.void_record USING btree (tenant_id, store_id, business_date);
DROP INDEX reporting.void_record_store_day_ix;
ALTER INDEX reporting.void_record_store_day_ix_tenant RENAME TO void_record_store_day_ix;
