-- R6 S74d: promotions and the loyalty program become per tenant; the store tables keep their store policies and gain the tenant one (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- No UPDATE runs: every new column takes a constant default (catalog-only, no rewrite), then the database default
-- shared.rls_tenant_id(), so an insert takes the bound tenant and an unbound one raises RS003. The bootstrap tenant
-- (P70 = none in production) is the fixed literal of every R6 migration and of TenantTestSupport.BOOTSTRAP.
-- Generated from the schema this module's earlier migrations built; every constraint keeps its name, so the
-- application's mapping of a violation by constraint name (UniqueConstraints) is unchanged.

-- (1)+(2) tenant_id on every table.
ALTER TABLE promotions.discount_limits
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.discount_limits
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.loyalty_account
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.loyalty_account
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.loyalty_allocation
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.loyalty_allocation
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.loyalty_point_entry
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.loyalty_point_entry
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.loyalty_program_scope
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.loyalty_program_scope
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.loyalty_program_version
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.loyalty_program_version
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.loyalty_reward
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.loyalty_reward
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.promotion
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.promotion
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.promotion_release
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.promotion_release
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.promotion_scope
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.promotion_scope
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.promotion_store
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.promotion_store
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE promotions.promotion_window
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE promotions.promotion_window
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
-- loyalty_program_scope has no key at all (its unique key has nullable columns): the policy helper needs an identity, so it gets
-- a surrogate id. A volatile default rewrites the table (a handful of scope rows, no UPDATE, no trigger).
ALTER TABLE promotions.loyalty_program_scope
    ADD COLUMN id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE promotions.loyalty_program_scope
    ADD CONSTRAINT loyalty_program_scope_pk PRIMARY KEY (id);

-- (3) UNIQUE (tenant_id, id) on every table with an id: the target of composite foreign keys.
ALTER TABLE promotions.loyalty_point_entry
    ADD CONSTRAINT loyalty_point_entry_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE promotions.loyalty_program_scope
    ADD CONSTRAINT loyalty_program_scope_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE promotions.loyalty_program_version
    ADD CONSTRAINT loyalty_program_version_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE promotions.loyalty_reward
    ADD CONSTRAINT loyalty_reward_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE promotions.promotion
    ADD CONSTRAINT promotion_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE promotions.promotion_release
    ADD CONSTRAINT promotion_release_tenant_id_uk UNIQUE (tenant_id, id);

-- (4) Business unique keys per tenant: create the (tenant_id, ...) one, then (after the foreign keys moved) drop
-- the old and keep its name.
ALTER TABLE promotions.loyalty_point_entry
    ADD CONSTRAINT loyalty_point_entry_reverses_uk_tenant UNIQUE (tenant_id, reverses_entry_id);
ALTER TABLE promotions.loyalty_point_entry
    ADD CONSTRAINT loyalty_point_entry_source_uk_tenant UNIQUE (tenant_id, source_type, source_id, kind);
ALTER TABLE promotions.loyalty_program_scope
    ADD CONSTRAINT loyalty_program_scope_uk_tenant UNIQUE NULLS NOT DISTINCT (tenant_id, version_id, item_id, family_id);
ALTER TABLE promotions.loyalty_program_version
    ADD CONSTRAINT loyalty_program_version_no_uk_tenant UNIQUE (tenant_id, version_no);
ALTER TABLE promotions.loyalty_reward
    ADD CONSTRAINT loyalty_reward_code_uk_tenant UNIQUE (tenant_id, version_id, code);
ALTER TABLE promotions.promotion
    ADD CONSTRAINT promotion_code_uk_tenant UNIQUE (tenant_id, code);
ALTER TABLE promotions.promotion_release
    ADD CONSTRAINT promotion_release_store_version_uk_tenant UNIQUE (tenant_id, store_id, version_no);
ALTER TABLE promotions.promotion_scope
    ADD CONSTRAINT promotion_scope_uk_tenant UNIQUE NULLS NOT DISTINCT (tenant_id, promotion_id, item_id, variant_id, family_id);
CREATE UNIQUE INDEX loyalty_program_version_published_uk_tenant ON promotions.loyalty_program_version USING btree (tenant_id, status) WHERE ((status)::text = 'PUBLISHED'::text);
CREATE UNIQUE INDEX promotion_release_one_published_per_store_ux_tenant ON promotions.promotion_release USING btree (tenant_id, store_id) WHERE ((status)::text = 'PUBLISHED'::text);
CREATE UNIQUE INDEX loyalty_account_tenant_pk_ix ON promotions.loyalty_account (tenant_id, customer_id);

-- (5) In-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, drop the
-- single-column one, keep its name.
ALTER TABLE promotions.loyalty_allocation
    ADD CONSTRAINT loyalty_allocation_debit_fk_tenant FOREIGN KEY (tenant_id, debit_entry_id)
        REFERENCES promotions.loyalty_point_entry (tenant_id, id) NOT VALID;
ALTER TABLE promotions.loyalty_allocation
    VALIDATE CONSTRAINT loyalty_allocation_debit_fk_tenant;
ALTER TABLE promotions.loyalty_allocation
    ADD CONSTRAINT loyalty_allocation_earn_fk_tenant FOREIGN KEY (tenant_id, earn_entry_id)
        REFERENCES promotions.loyalty_point_entry (tenant_id, id) NOT VALID;
ALTER TABLE promotions.loyalty_allocation
    VALIDATE CONSTRAINT loyalty_allocation_earn_fk_tenant;
ALTER TABLE promotions.loyalty_point_entry
    ADD CONSTRAINT loyalty_point_entry_account_fk_tenant FOREIGN KEY (tenant_id, customer_id)
        REFERENCES promotions.loyalty_account (tenant_id, customer_id) NOT VALID;
ALTER TABLE promotions.loyalty_point_entry
    VALIDATE CONSTRAINT loyalty_point_entry_account_fk_tenant;
ALTER TABLE promotions.loyalty_point_entry
    ADD CONSTRAINT loyalty_point_entry_reverses_fk_tenant FOREIGN KEY (tenant_id, reverses_entry_id)
        REFERENCES promotions.loyalty_point_entry (tenant_id, id) NOT VALID;
ALTER TABLE promotions.loyalty_point_entry
    VALIDATE CONSTRAINT loyalty_point_entry_reverses_fk_tenant;
ALTER TABLE promotions.loyalty_point_entry
    ADD CONSTRAINT loyalty_point_entry_reward_fk_tenant FOREIGN KEY (tenant_id, reward_id)
        REFERENCES promotions.loyalty_reward (tenant_id, id) NOT VALID;
ALTER TABLE promotions.loyalty_point_entry
    VALIDATE CONSTRAINT loyalty_point_entry_reward_fk_tenant;
ALTER TABLE promotions.loyalty_point_entry
    ADD CONSTRAINT loyalty_point_entry_version_fk_tenant FOREIGN KEY (tenant_id, program_version_id)
        REFERENCES promotions.loyalty_program_version (tenant_id, id) NOT VALID;
ALTER TABLE promotions.loyalty_point_entry
    VALIDATE CONSTRAINT loyalty_point_entry_version_fk_tenant;
ALTER TABLE promotions.loyalty_program_scope
    ADD CONSTRAINT loyalty_program_scope_version_fk_tenant FOREIGN KEY (tenant_id, version_id)
        REFERENCES promotions.loyalty_program_version (tenant_id, id) NOT VALID;
ALTER TABLE promotions.loyalty_program_scope
    VALIDATE CONSTRAINT loyalty_program_scope_version_fk_tenant;
ALTER TABLE promotions.loyalty_reward
    ADD CONSTRAINT loyalty_reward_version_fk_tenant FOREIGN KEY (tenant_id, version_id)
        REFERENCES promotions.loyalty_program_version (tenant_id, id) NOT VALID;
ALTER TABLE promotions.loyalty_reward
    VALIDATE CONSTRAINT loyalty_reward_version_fk_tenant;
ALTER TABLE promotions.promotion_scope
    ADD CONSTRAINT promotion_scope_promotion_fk_tenant FOREIGN KEY (tenant_id, promotion_id)
        REFERENCES promotions.promotion (tenant_id, id) ON DELETE CASCADE NOT VALID;
ALTER TABLE promotions.promotion_scope
    VALIDATE CONSTRAINT promotion_scope_promotion_fk_tenant;
ALTER TABLE promotions.promotion_store
    ADD CONSTRAINT promotion_store_promotion_fk_tenant FOREIGN KEY (tenant_id, promotion_id)
        REFERENCES promotions.promotion (tenant_id, id) ON DELETE CASCADE NOT VALID;
ALTER TABLE promotions.promotion_store
    VALIDATE CONSTRAINT promotion_store_promotion_fk_tenant;
ALTER TABLE promotions.promotion_window
    ADD CONSTRAINT promotion_window_promotion_fk_tenant FOREIGN KEY (tenant_id, promotion_id)
        REFERENCES promotions.promotion (tenant_id, id) ON DELETE CASCADE NOT VALID;
ALTER TABLE promotions.promotion_window
    VALIDATE CONSTRAINT promotion_window_promotion_fk_tenant;
ALTER TABLE promotions.loyalty_allocation
    DROP CONSTRAINT loyalty_allocation_debit_fk;
ALTER TABLE promotions.loyalty_allocation
    DROP CONSTRAINT loyalty_allocation_earn_fk;
ALTER TABLE promotions.loyalty_point_entry
    DROP CONSTRAINT loyalty_point_entry_account_fk;
ALTER TABLE promotions.loyalty_point_entry
    DROP CONSTRAINT loyalty_point_entry_reverses_fk;
ALTER TABLE promotions.loyalty_point_entry
    DROP CONSTRAINT loyalty_point_entry_reward_fk;
ALTER TABLE promotions.loyalty_point_entry
    DROP CONSTRAINT loyalty_point_entry_version_fk;
ALTER TABLE promotions.loyalty_program_scope
    DROP CONSTRAINT loyalty_program_scope_version_fk;
ALTER TABLE promotions.loyalty_reward
    DROP CONSTRAINT loyalty_reward_version_fk;
ALTER TABLE promotions.promotion_scope
    DROP CONSTRAINT promotion_scope_promotion_fk;
ALTER TABLE promotions.promotion_store
    DROP CONSTRAINT promotion_store_promotion_fk;
ALTER TABLE promotions.promotion_window
    DROP CONSTRAINT promotion_window_promotion_fk;
ALTER TABLE promotions.loyalty_allocation
    RENAME CONSTRAINT loyalty_allocation_debit_fk_tenant TO loyalty_allocation_debit_fk;
ALTER TABLE promotions.loyalty_allocation
    RENAME CONSTRAINT loyalty_allocation_earn_fk_tenant TO loyalty_allocation_earn_fk;
ALTER TABLE promotions.loyalty_point_entry
    RENAME CONSTRAINT loyalty_point_entry_account_fk_tenant TO loyalty_point_entry_account_fk;
ALTER TABLE promotions.loyalty_point_entry
    RENAME CONSTRAINT loyalty_point_entry_reverses_fk_tenant TO loyalty_point_entry_reverses_fk;
ALTER TABLE promotions.loyalty_point_entry
    RENAME CONSTRAINT loyalty_point_entry_reward_fk_tenant TO loyalty_point_entry_reward_fk;
ALTER TABLE promotions.loyalty_point_entry
    RENAME CONSTRAINT loyalty_point_entry_version_fk_tenant TO loyalty_point_entry_version_fk;
ALTER TABLE promotions.loyalty_program_scope
    RENAME CONSTRAINT loyalty_program_scope_version_fk_tenant TO loyalty_program_scope_version_fk;
ALTER TABLE promotions.loyalty_reward
    RENAME CONSTRAINT loyalty_reward_version_fk_tenant TO loyalty_reward_version_fk;
ALTER TABLE promotions.promotion_scope
    RENAME CONSTRAINT promotion_scope_promotion_fk_tenant TO promotion_scope_promotion_fk;
ALTER TABLE promotions.promotion_store
    RENAME CONSTRAINT promotion_store_promotion_fk_tenant TO promotion_store_promotion_fk;
ALTER TABLE promotions.promotion_window
    RENAME CONSTRAINT promotion_window_promotion_fk_tenant TO promotion_window_promotion_fk;

-- Old unique keys out (nothing references them any more), the new ones take their names.
ALTER TABLE promotions.loyalty_point_entry
    DROP CONSTRAINT loyalty_point_entry_reverses_uk;
ALTER TABLE promotions.loyalty_point_entry
    DROP CONSTRAINT loyalty_point_entry_source_uk;
ALTER TABLE promotions.loyalty_program_scope
    DROP CONSTRAINT loyalty_program_scope_uk;
ALTER TABLE promotions.loyalty_program_version
    DROP CONSTRAINT loyalty_program_version_no_uk;
ALTER TABLE promotions.loyalty_reward
    DROP CONSTRAINT loyalty_reward_code_uk;
ALTER TABLE promotions.promotion
    DROP CONSTRAINT promotion_code_uk;
ALTER TABLE promotions.promotion_release
    DROP CONSTRAINT promotion_release_store_version_uk;
ALTER TABLE promotions.promotion_scope
    DROP CONSTRAINT promotion_scope_uk;
DROP INDEX promotions.loyalty_program_version_published_uk;
DROP INDEX promotions.promotion_release_one_published_per_store_ux;
ALTER TABLE promotions.loyalty_point_entry
    RENAME CONSTRAINT loyalty_point_entry_reverses_uk_tenant TO loyalty_point_entry_reverses_uk;
ALTER TABLE promotions.loyalty_point_entry
    RENAME CONSTRAINT loyalty_point_entry_source_uk_tenant TO loyalty_point_entry_source_uk;
ALTER TABLE promotions.loyalty_program_scope
    RENAME CONSTRAINT loyalty_program_scope_uk_tenant TO loyalty_program_scope_uk;
ALTER TABLE promotions.loyalty_program_version
    RENAME CONSTRAINT loyalty_program_version_no_uk_tenant TO loyalty_program_version_no_uk;
ALTER TABLE promotions.loyalty_reward
    RENAME CONSTRAINT loyalty_reward_code_uk_tenant TO loyalty_reward_code_uk;
ALTER TABLE promotions.promotion
    RENAME CONSTRAINT promotion_code_uk_tenant TO promotion_code_uk;
ALTER TABLE promotions.promotion_release
    RENAME CONSTRAINT promotion_release_store_version_uk_tenant TO promotion_release_store_version_uk;
ALTER TABLE promotions.promotion_scope
    RENAME CONSTRAINT promotion_scope_uk_tenant TO promotion_scope_uk;
ALTER INDEX promotions.loyalty_program_version_published_uk_tenant RENAME TO loyalty_program_version_published_uk;
ALTER INDEX promotions.promotion_release_one_published_per_store_ux_tenant RENAME TO promotion_release_one_published_per_store_ux;

-- (3b) A table with no id keeps its identity in a primary key that leads with tenant_id (spec: the policy helper
-- asserts it).
ALTER TABLE promotions.discount_limits
    DROP CONSTRAINT discount_limits_pk;
ALTER TABLE promotions.discount_limits
    ADD CONSTRAINT discount_limits_pk PRIMARY KEY (tenant_id, store_id);
ALTER TABLE promotions.loyalty_account
    DROP CONSTRAINT loyalty_account_pk;
ALTER TABLE promotions.loyalty_account
    ADD CONSTRAINT loyalty_account_pk PRIMARY KEY USING INDEX loyalty_account_tenant_pk_ix;
ALTER TABLE promotions.loyalty_allocation
    DROP CONSTRAINT loyalty_allocation_pk;
ALTER TABLE promotions.loyalty_allocation
    ADD CONSTRAINT loyalty_allocation_pk PRIMARY KEY (tenant_id, debit_entry_id, earn_entry_id);
ALTER TABLE promotions.promotion_scope
    DROP CONSTRAINT promotion_scope_pk;
ALTER TABLE promotions.promotion_scope
    ADD CONSTRAINT promotion_scope_pk PRIMARY KEY (tenant_id, promotion_id, position);
ALTER TABLE promotions.promotion_store
    DROP CONSTRAINT promotion_store_pk;
ALTER TABLE promotions.promotion_store
    ADD CONSTRAINT promotion_store_pk PRIMARY KEY (tenant_id, promotion_id, store_id);
ALTER TABLE promotions.promotion_window
    DROP CONSTRAINT promotion_window_pk;
ALTER TABLE promotions.promotion_window
    ADD CONSTRAINT promotion_window_pk PRIMARY KEY (tenant_id, promotion_id, position);

-- (6) The tenant policy through the helper, then the registries (out of UNSCOPED).
DELETE FROM shared.rls_unscoped_table
WHERE table_name IN ('promotions.loyalty_account'::regclass,
                     'promotions.loyalty_allocation'::regclass,
                     'promotions.loyalty_point_entry'::regclass,
                     'promotions.loyalty_program_scope'::regclass,
                     'promotions.loyalty_program_version'::regclass,
                     'promotions.loyalty_reward'::regclass,
                     'promotions.promotion'::regclass,
                     'promotions.promotion_scope'::regclass,
                     'promotions.promotion_store'::regclass,
                     'promotions.promotion_window'::regclass);
SELECT shared.rls_protect_tenant('promotions.discount_limits');
SELECT shared.rls_protect_tenant_table('promotions.loyalty_account');
SELECT shared.rls_protect_tenant_table('promotions.loyalty_allocation');
SELECT shared.rls_protect_tenant_table('promotions.loyalty_point_entry');
SELECT shared.rls_protect_tenant_table('promotions.loyalty_program_scope');
SELECT shared.rls_protect_tenant_table('promotions.loyalty_program_version');
SELECT shared.rls_protect_tenant_table('promotions.loyalty_reward');
SELECT shared.rls_protect_tenant_table('promotions.promotion');
SELECT shared.rls_protect_tenant('promotions.promotion_release');
SELECT shared.rls_protect_tenant_table('promotions.promotion_scope');
SELECT shared.rls_protect_tenant_table('promotions.promotion_store');
SELECT shared.rls_protect_tenant_table('promotions.promotion_window');
