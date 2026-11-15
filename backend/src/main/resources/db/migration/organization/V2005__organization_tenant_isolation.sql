-- R6 S71: organization becomes tenant-isolated (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- company_profile, store and terminal are TENANT class (formerly UNSCOPED, D167): a restrictive tenant policy and
-- no store predicate. No UPDATE runs: every new column takes a constant default (catalog-only, no rewrite), then the
-- database default shared.rls_tenant_id(), so an insert takes the bound tenant and an unbound one raises RS003.
-- The bootstrap tenant (P70 = none in production) is the fixed literal of every R6 migration and of
-- TenantTestSupport.BOOTSTRAP.

-- (1)+(2) tenant_id on every table, parents first.
ALTER TABLE organization.company_profile
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE organization.company_profile
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE organization.store
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE organization.store
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE organization.terminal
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE organization.terminal
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- The company profile is one row per tenant (D174): the constant TRUE key of D30 becomes a uuid id (the existing
-- bootstrap row takes a fixed literal; new profiles get a UUIDv7 from the application) and UNIQUE (tenant_id).
ALTER TABLE organization.company_profile
    ADD COLUMN profile_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000002';
ALTER TABLE organization.company_profile
    ALTER COLUMN profile_id DROP DEFAULT;
ALTER TABLE organization.company_profile
    DROP CONSTRAINT company_profile_pk,
    DROP CONSTRAINT company_profile_single_row_ck,
    DROP COLUMN id;
ALTER TABLE organization.company_profile
    RENAME COLUMN profile_id TO id;
ALTER TABLE organization.company_profile
    ADD CONSTRAINT company_profile_pk PRIMARY KEY (id),
    ADD CONSTRAINT company_profile_tenant_uk UNIQUE (tenant_id);

-- (3) UNIQUE (tenant_id, id) on every table, parents before children reference them.
ALTER TABLE organization.company_profile
    ADD CONSTRAINT company_profile_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE organization.store
    ADD CONSTRAINT store_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE organization.terminal
    ADD CONSTRAINT terminal_tenant_id_uk UNIQUE (tenant_id, id);

-- (4) Business unique keys per tenant: create the (tenant_id, ...) one, drop the old, keep the old name (the
-- application maps a violation by constraint name, UniqueConstraints).
ALTER TABLE organization.store
    ADD CONSTRAINT store_tenant_code_uk UNIQUE (tenant_id, code);
ALTER TABLE organization.store
    DROP CONSTRAINT store_code_uk;
ALTER TABLE organization.store
    RENAME CONSTRAINT store_tenant_code_uk TO store_code_uk;

ALTER TABLE organization.terminal
    ADD CONSTRAINT terminal_tenant_store_code_uk UNIQUE (tenant_id, store_id, code),
    ADD CONSTRAINT terminal_tenant_enrollment_code_hash_uk UNIQUE (tenant_id, enrollment_code_hash),
    ADD CONSTRAINT terminal_tenant_device_token_hash_uk UNIQUE (tenant_id, device_token_hash);
ALTER TABLE organization.terminal
    DROP CONSTRAINT terminal_store_code_uk,
    DROP CONSTRAINT terminal_enrollment_code_hash_uk,
    DROP CONSTRAINT terminal_device_token_hash_uk;
ALTER TABLE organization.terminal
    RENAME CONSTRAINT terminal_tenant_store_code_uk TO terminal_store_code_uk;
ALTER TABLE organization.terminal
    RENAME CONSTRAINT terminal_tenant_enrollment_code_hash_uk TO terminal_enrollment_code_hash_uk;
ALTER TABLE organization.terminal
    RENAME CONSTRAINT terminal_tenant_device_token_hash_uk TO terminal_device_token_hash_uk;

-- (5) The in-module foreign key becomes composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, then drop
-- the single-column one and keep its name.
ALTER TABLE organization.terminal
    ADD CONSTRAINT terminal_tenant_store_fk FOREIGN KEY (tenant_id, store_id)
        REFERENCES organization.store (tenant_id, id) NOT VALID;
ALTER TABLE organization.terminal
    VALIDATE CONSTRAINT terminal_tenant_store_fk;
ALTER TABLE organization.terminal
    DROP CONSTRAINT terminal_store_fk;
ALTER TABLE organization.terminal
    RENAME CONSTRAINT terminal_tenant_store_fk TO terminal_store_fk;

-- (6) The policies through the helper, then the registries (out of UNSCOPED).
DELETE FROM shared.rls_unscoped_table
WHERE table_name IN ('organization.company_profile'::regclass, 'organization.store'::regclass,
                     'organization.terminal'::regclass);
SELECT shared.rls_protect_tenant_table('organization.company_profile');
SELECT shared.rls_protect_tenant_table('organization.store');
SELECT shared.rls_protect_tenant_table('organization.terminal');
