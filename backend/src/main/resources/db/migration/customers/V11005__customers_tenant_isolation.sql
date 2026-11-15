-- R6 S74c: customers becomes tenant-isolated (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- All eight tables were company-wide (UNSCOPED, P38, D167); they become TENANT class: a restrictive tenant policy and
-- no store predicate (a ledger entry's store_id stays the origin of the entry, not its owner). No UPDATE runs: every
-- new column takes a constant default (catalog-only, no rewrite; the append-only triggers never fire), then the
-- database default shared.rls_tenant_id(), so an insert takes the bound tenant and an unbound one raises RS003. The
-- bootstrap tenant is the fixed literal of every R6 migration and of TenantTestSupport.BOOTSTRAP.

-- (1)+(2) tenant_id on every table.
ALTER TABLE customers.customer
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE customers.customer
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE customers.customer_phone
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE customers.customer_phone
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE customers.customer_address
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE customers.customer_address
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE customers.credit_account
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE customers.credit_account
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE customers.credit_limit_change
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE customers.credit_limit_change
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE customers.credit_reservation
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE customers.credit_reservation
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE customers.credit_ledger_entry
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE customers.credit_ledger_entry
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE customers.credit_settlement
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE customers.credit_settlement
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) UNIQUE (tenant_id, id) on every table with an id. credit_account is keyed by its customer (no id): its key
-- (tenant_id, customer_id) is built first as a unique index, referenced by the composite foreign keys below, and then
-- becomes the primary key in place of (customer_id).
ALTER TABLE customers.customer
    ADD CONSTRAINT customer_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE customers.customer_phone
    ADD CONSTRAINT customer_phone_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE customers.customer_address
    ADD CONSTRAINT customer_address_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE customers.credit_limit_change
    ADD CONSTRAINT credit_limit_change_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE customers.credit_reservation
    ADD CONSTRAINT credit_reservation_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE customers.credit_ledger_entry
    ADD CONSTRAINT credit_ledger_entry_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE customers.credit_settlement
    ADD CONSTRAINT credit_settlement_tenant_id_uk UNIQUE (tenant_id, id);
CREATE UNIQUE INDEX credit_account_tenant_customer_ux ON customers.credit_account (tenant_id, customer_id);

-- (4) Business unique keys per tenant: create the (tenant_id, ...) one, drop the old, keep the old name.
ALTER TABLE customers.customer_phone
    ADD CONSTRAINT customer_phone_customer_digits_uk_tenant UNIQUE (tenant_id, customer_id, digits);
ALTER TABLE customers.customer_phone
    DROP CONSTRAINT customer_phone_customer_digits_uk;
ALTER TABLE customers.customer_phone
    RENAME CONSTRAINT customer_phone_customer_digits_uk_tenant TO customer_phone_customer_digits_uk;
CREATE UNIQUE INDEX customer_phone_primary_uk_tenant ON customers.customer_phone (tenant_id, customer_id)
    WHERE is_primary;
DROP INDEX customers.customer_phone_primary_uk;
ALTER INDEX customers.customer_phone_primary_uk_tenant RENAME TO customer_phone_primary_uk;
ALTER TABLE customers.credit_reservation
    ADD CONSTRAINT credit_reservation_charge_uk_tenant UNIQUE (tenant_id, charge_id);
ALTER TABLE customers.credit_reservation
    DROP CONSTRAINT credit_reservation_charge_uk;
ALTER TABLE customers.credit_reservation
    RENAME CONSTRAINT credit_reservation_charge_uk_tenant TO credit_reservation_charge_uk;
ALTER TABLE customers.credit_ledger_entry
    ADD CONSTRAINT credit_ledger_entry_source_uk_tenant UNIQUE (tenant_id, source_type, source_id, kind);
ALTER TABLE customers.credit_ledger_entry
    DROP CONSTRAINT credit_ledger_entry_source_uk;
ALTER TABLE customers.credit_ledger_entry
    RENAME CONSTRAINT credit_ledger_entry_source_uk_tenant TO credit_ledger_entry_source_uk;
ALTER TABLE customers.credit_settlement
    ADD CONSTRAINT credit_settlement_uk_tenant UNIQUE NULLS NOT DISTINCT (tenant_id, credit_entry_id, debit_entry_id, reversal_entry_id);
ALTER TABLE customers.credit_settlement
    DROP CONSTRAINT credit_settlement_uk;
ALTER TABLE customers.credit_settlement
    RENAME CONSTRAINT credit_settlement_uk_tenant TO credit_settlement_uk;
CREATE INDEX customer_tax_id_ix_tenant ON customers.customer (tenant_id, tax_id);
DROP INDEX customers.customer_tax_id_ix;
ALTER INDEX customers.customer_tax_id_ix_tenant RENAME TO customer_tax_id_ix;

-- (5) In-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, then drop the
-- single-column one and keep its name.
ALTER TABLE customers.customer_phone
    ADD CONSTRAINT customer_phone_customer_fk_tenant FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers.customer (tenant_id, id) NOT VALID;
ALTER TABLE customers.customer_phone
    VALIDATE CONSTRAINT customer_phone_customer_fk_tenant;
ALTER TABLE customers.customer_phone
    DROP CONSTRAINT customer_phone_customer_fk;
ALTER TABLE customers.customer_phone
    RENAME CONSTRAINT customer_phone_customer_fk_tenant TO customer_phone_customer_fk;
ALTER TABLE customers.customer_address
    ADD CONSTRAINT customer_address_customer_fk_tenant FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers.customer (tenant_id, id) NOT VALID;
ALTER TABLE customers.customer_address
    VALIDATE CONSTRAINT customer_address_customer_fk_tenant;
ALTER TABLE customers.customer_address
    DROP CONSTRAINT customer_address_customer_fk;
ALTER TABLE customers.customer_address
    RENAME CONSTRAINT customer_address_customer_fk_tenant TO customer_address_customer_fk;
ALTER TABLE customers.credit_account
    ADD CONSTRAINT credit_account_customer_fk_tenant FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers.customer (tenant_id, id) NOT VALID;
ALTER TABLE customers.credit_account
    VALIDATE CONSTRAINT credit_account_customer_fk_tenant;
ALTER TABLE customers.credit_account
    DROP CONSTRAINT credit_account_customer_fk;
ALTER TABLE customers.credit_account
    RENAME CONSTRAINT credit_account_customer_fk_tenant TO credit_account_customer_fk;
ALTER TABLE customers.credit_limit_change
    ADD CONSTRAINT credit_limit_change_account_fk_tenant FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers.credit_account (tenant_id, customer_id) NOT VALID;
ALTER TABLE customers.credit_limit_change
    VALIDATE CONSTRAINT credit_limit_change_account_fk_tenant;
ALTER TABLE customers.credit_limit_change
    DROP CONSTRAINT credit_limit_change_account_fk;
ALTER TABLE customers.credit_limit_change
    RENAME CONSTRAINT credit_limit_change_account_fk_tenant TO credit_limit_change_account_fk;
ALTER TABLE customers.credit_reservation
    ADD CONSTRAINT credit_reservation_account_fk_tenant FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers.credit_account (tenant_id, customer_id) NOT VALID;
ALTER TABLE customers.credit_reservation
    VALIDATE CONSTRAINT credit_reservation_account_fk_tenant;
ALTER TABLE customers.credit_reservation
    DROP CONSTRAINT credit_reservation_account_fk;
ALTER TABLE customers.credit_reservation
    RENAME CONSTRAINT credit_reservation_account_fk_tenant TO credit_reservation_account_fk;
ALTER TABLE customers.credit_ledger_entry
    ADD CONSTRAINT credit_ledger_entry_account_fk_tenant FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers.credit_account (tenant_id, customer_id) NOT VALID;
ALTER TABLE customers.credit_ledger_entry
    VALIDATE CONSTRAINT credit_ledger_entry_account_fk_tenant;
ALTER TABLE customers.credit_ledger_entry
    DROP CONSTRAINT credit_ledger_entry_account_fk;
ALTER TABLE customers.credit_ledger_entry
    RENAME CONSTRAINT credit_ledger_entry_account_fk_tenant TO credit_ledger_entry_account_fk;
ALTER TABLE customers.credit_ledger_entry
    ADD CONSTRAINT credit_ledger_entry_reversed_fk_tenant FOREIGN KEY (tenant_id, reversed_entry_id)
        REFERENCES customers.credit_ledger_entry (tenant_id, id) NOT VALID;
ALTER TABLE customers.credit_ledger_entry
    VALIDATE CONSTRAINT credit_ledger_entry_reversed_fk_tenant;
ALTER TABLE customers.credit_ledger_entry
    DROP CONSTRAINT credit_ledger_entry_reversed_fk;
ALTER TABLE customers.credit_ledger_entry
    RENAME CONSTRAINT credit_ledger_entry_reversed_fk_tenant TO credit_ledger_entry_reversed_fk;
ALTER TABLE customers.credit_settlement
    ADD CONSTRAINT credit_settlement_credit_fk_tenant FOREIGN KEY (tenant_id, credit_entry_id)
        REFERENCES customers.credit_ledger_entry (tenant_id, id) NOT VALID;
ALTER TABLE customers.credit_settlement
    VALIDATE CONSTRAINT credit_settlement_credit_fk_tenant;
ALTER TABLE customers.credit_settlement
    DROP CONSTRAINT credit_settlement_credit_fk;
ALTER TABLE customers.credit_settlement
    RENAME CONSTRAINT credit_settlement_credit_fk_tenant TO credit_settlement_credit_fk;
ALTER TABLE customers.credit_settlement
    ADD CONSTRAINT credit_settlement_debit_fk_tenant FOREIGN KEY (tenant_id, debit_entry_id)
        REFERENCES customers.credit_ledger_entry (tenant_id, id) NOT VALID;
ALTER TABLE customers.credit_settlement
    VALIDATE CONSTRAINT credit_settlement_debit_fk_tenant;
ALTER TABLE customers.credit_settlement
    DROP CONSTRAINT credit_settlement_debit_fk;
ALTER TABLE customers.credit_settlement
    RENAME CONSTRAINT credit_settlement_debit_fk_tenant TO credit_settlement_debit_fk;
ALTER TABLE customers.credit_settlement
    ADD CONSTRAINT credit_settlement_reversal_fk_tenant FOREIGN KEY (tenant_id, reversal_entry_id)
        REFERENCES customers.credit_ledger_entry (tenant_id, id) NOT VALID;
ALTER TABLE customers.credit_settlement
    VALIDATE CONSTRAINT credit_settlement_reversal_fk_tenant;
ALTER TABLE customers.credit_settlement
    DROP CONSTRAINT credit_settlement_reversal_fk;
ALTER TABLE customers.credit_settlement
    RENAME CONSTRAINT credit_settlement_reversal_fk_tenant TO credit_settlement_reversal_fk;

-- The old single-column primary key of credit_account goes only now, when no foreign key needs it; the unique index
-- built above takes its place and its name.
ALTER TABLE customers.credit_account
    DROP CONSTRAINT credit_account_pk;
ALTER TABLE customers.credit_account
    ADD CONSTRAINT credit_account_pk PRIMARY KEY USING INDEX credit_account_tenant_customer_ux;

-- (6) The policies through the helper, then the registries (out of UNSCOPED).
DELETE FROM shared.rls_unscoped_table
WHERE table_name IN ('customers.customer'::regclass, 'customers.customer_phone'::regclass, 'customers.customer_address'::regclass, 'customers.credit_account'::regclass, 'customers.credit_limit_change'::regclass, 'customers.credit_reservation'::regclass, 'customers.credit_ledger_entry'::regclass, 'customers.credit_settlement'::regclass);
SELECT shared.rls_protect_tenant_table('customers.customer');
SELECT shared.rls_protect_tenant_table('customers.customer_phone');
SELECT shared.rls_protect_tenant_table('customers.customer_address');
SELECT shared.rls_protect_tenant_table('customers.credit_account');
SELECT shared.rls_protect_tenant_table('customers.credit_limit_change');
SELECT shared.rls_protect_tenant_table('customers.credit_reservation');
SELECT shared.rls_protect_tenant_table('customers.credit_ledger_entry');
SELECT shared.rls_protect_tenant_table('customers.credit_settlement');
