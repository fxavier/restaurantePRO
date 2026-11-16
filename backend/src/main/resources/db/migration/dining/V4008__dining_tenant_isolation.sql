-- R6 S74a: dining becomes tenant-isolated (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- Every dining table is STORE class (RLS run) and gains the restrictive tenant policy on top: the store policy stays.
-- No UPDATE runs. The tables without an id (reservation_table, dining_settings) lead their primary key with tenant_id.

-- (1)+(2) tenant_id on every table: a constant default first (catalog-only, no rewrite, no UPDATE), then the
-- database default, so an insert takes the bound tenant and an unbound one raises RS003.
ALTER TABLE dining.room_area
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE dining.room_area
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE dining.restaurant_table
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE dining.restaurant_table
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE dining.table_occupation
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE dining.table_occupation
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE dining.reservation
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE dining.reservation
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE dining.reservation_table
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE dining.reservation_table
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE dining.dining_settings
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE dining.dining_settings
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) Identity includes the tenant: a primary key that leads with tenant_id where the table has no id, and
-- UNIQUE (tenant_id, id) on every table with one (the target of composite foreign keys).
ALTER TABLE dining.reservation_table
    DROP CONSTRAINT reservation_table_pk,
    ADD CONSTRAINT reservation_table_pk PRIMARY KEY (tenant_id, reservation_id, table_id);
ALTER TABLE dining.dining_settings
    DROP CONSTRAINT dining_settings_pk,
    ADD CONSTRAINT dining_settings_pk PRIMARY KEY (tenant_id, store_id);
ALTER TABLE dining.room_area
    ADD CONSTRAINT room_area_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE dining.restaurant_table
    ADD CONSTRAINT restaurant_table_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE dining.table_occupation
    ADD CONSTRAINT table_occupation_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE dining.reservation
    ADD CONSTRAINT reservation_tenant_id_uk UNIQUE (tenant_id, id);

-- (4) Business and reference keys per tenant: the (tenant_id, ...) key is created under a temporary name, the old
-- one dropped after the foreign keys that used it, and the old name restored (the application maps a violation
-- by constraint name, UniqueConstraints).
ALTER TABLE dining.room_area
    ADD CONSTRAINT tn_room_area_id_store_uk UNIQUE (tenant_id, id, store_id);
ALTER TABLE dining.room_area
    ADD CONSTRAINT tn_room_area_store_code_uk UNIQUE (tenant_id, store_id, code);
ALTER TABLE dining.restaurant_table
    ADD CONSTRAINT tn_restaurant_table_id_store_uk UNIQUE (tenant_id, id, store_id);
ALTER TABLE dining.restaurant_table
    ADD CONSTRAINT tn_restaurant_table_store_code_uk UNIQUE (tenant_id, store_id, code);
ALTER TABLE dining.table_occupation
    ADD CONSTRAINT tn_table_occupation_id_store_uk UNIQUE (tenant_id, id, store_id);
ALTER TABLE dining.reservation
    ADD CONSTRAINT tn_reservation_id_store_uk UNIQUE (tenant_id, id, store_id);
CREATE UNIQUE INDEX tn_reservation_occupation_uk ON dining.reservation (tenant_id, occupation_id) WHERE occupation_id IS NOT NULL;
CREATE UNIQUE INDEX tn_table_occupation_open_order_uk ON dining.table_occupation (tenant_id, order_id) WHERE closed_at IS NULL;
CREATE UNIQUE INDEX tn_table_occupation_open_table_uk ON dining.table_occupation (tenant_id, table_id) WHERE closed_at IS NULL;

-- (5) In-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, then the
-- single-column one is dropped and the name kept.
ALTER TABLE dining.restaurant_table
    ADD CONSTRAINT tn_restaurant_table_area_same_store_fk FOREIGN KEY (tenant_id, area_id, store_id)
        REFERENCES dining.room_area (tenant_id, id, store_id) NOT VALID;
ALTER TABLE dining.restaurant_table
    VALIDATE CONSTRAINT tn_restaurant_table_area_same_store_fk;
ALTER TABLE dining.table_occupation
    ADD CONSTRAINT tn_table_occupation_table_same_store_fk FOREIGN KEY (tenant_id, table_id, store_id)
        REFERENCES dining.restaurant_table (tenant_id, id, store_id) NOT VALID;
ALTER TABLE dining.table_occupation
    VALIDATE CONSTRAINT tn_table_occupation_table_same_store_fk;
ALTER TABLE dining.reservation
    ADD CONSTRAINT tn_reservation_occupation_same_store_fk FOREIGN KEY (tenant_id, occupation_id, store_id)
        REFERENCES dining.table_occupation (tenant_id, id, store_id) NOT VALID;
ALTER TABLE dining.reservation
    VALIDATE CONSTRAINT tn_reservation_occupation_same_store_fk;
ALTER TABLE dining.reservation_table
    ADD CONSTRAINT tn_reservation_table_reservation_same_store_fk FOREIGN KEY (tenant_id, reservation_id, store_id)
        REFERENCES dining.reservation (tenant_id, id, store_id) NOT VALID;
ALTER TABLE dining.reservation_table
    VALIDATE CONSTRAINT tn_reservation_table_reservation_same_store_fk;
ALTER TABLE dining.reservation_table
    ADD CONSTRAINT tn_reservation_table_table_same_store_fk FOREIGN KEY (tenant_id, table_id, store_id)
        REFERENCES dining.restaurant_table (tenant_id, id, store_id) NOT VALID;
ALTER TABLE dining.reservation_table
    VALIDATE CONSTRAINT tn_reservation_table_table_same_store_fk;
ALTER TABLE dining.restaurant_table
    DROP CONSTRAINT restaurant_table_area_same_store_fk;
ALTER TABLE dining.table_occupation
    DROP CONSTRAINT table_occupation_table_same_store_fk;
ALTER TABLE dining.reservation
    DROP CONSTRAINT reservation_occupation_same_store_fk;
ALTER TABLE dining.reservation_table
    DROP CONSTRAINT reservation_table_reservation_same_store_fk;
ALTER TABLE dining.reservation_table
    DROP CONSTRAINT reservation_table_table_same_store_fk;
ALTER TABLE dining.room_area
    DROP CONSTRAINT room_area_id_store_uk;
ALTER TABLE dining.room_area
    DROP CONSTRAINT room_area_store_code_uk;
ALTER TABLE dining.restaurant_table
    DROP CONSTRAINT restaurant_table_id_store_uk;
ALTER TABLE dining.restaurant_table
    DROP CONSTRAINT restaurant_table_store_code_uk;
ALTER TABLE dining.table_occupation
    DROP CONSTRAINT table_occupation_id_store_uk;
ALTER TABLE dining.reservation
    DROP CONSTRAINT reservation_id_store_uk;
DROP INDEX dining.reservation_occupation_uk;
DROP INDEX dining.table_occupation_open_order_uk;
DROP INDEX dining.table_occupation_open_table_uk;
ALTER TABLE dining.room_area
    RENAME CONSTRAINT tn_room_area_id_store_uk TO room_area_id_store_uk;
ALTER TABLE dining.room_area
    RENAME CONSTRAINT tn_room_area_store_code_uk TO room_area_store_code_uk;
ALTER TABLE dining.restaurant_table
    RENAME CONSTRAINT tn_restaurant_table_id_store_uk TO restaurant_table_id_store_uk;
ALTER TABLE dining.restaurant_table
    RENAME CONSTRAINT tn_restaurant_table_store_code_uk TO restaurant_table_store_code_uk;
ALTER TABLE dining.table_occupation
    RENAME CONSTRAINT tn_table_occupation_id_store_uk TO table_occupation_id_store_uk;
ALTER TABLE dining.reservation
    RENAME CONSTRAINT tn_reservation_id_store_uk TO reservation_id_store_uk;
ALTER TABLE dining.restaurant_table
    RENAME CONSTRAINT tn_restaurant_table_area_same_store_fk TO restaurant_table_area_same_store_fk;
ALTER TABLE dining.table_occupation
    RENAME CONSTRAINT tn_table_occupation_table_same_store_fk TO table_occupation_table_same_store_fk;
ALTER TABLE dining.reservation
    RENAME CONSTRAINT tn_reservation_occupation_same_store_fk TO reservation_occupation_same_store_fk;
ALTER TABLE dining.reservation_table
    RENAME CONSTRAINT tn_reservation_table_reservation_same_store_fk TO reservation_table_reservation_same_store_fk;
ALTER TABLE dining.reservation_table
    RENAME CONSTRAINT tn_reservation_table_table_same_store_fk TO reservation_table_table_same_store_fk;
ALTER INDEX dining.tn_reservation_occupation_uk RENAME TO reservation_occupation_uk;
ALTER INDEX dining.tn_table_occupation_open_order_uk RENAME TO table_occupation_open_order_uk;
ALTER INDEX dining.tn_table_occupation_open_table_uk RENAME TO table_occupation_open_table_uk;

-- D180 Indexes: every composite index that led with store_id now leads with tenant_id (same name and predicate).
CREATE INDEX tn_reservation_booked_store_starts_ix ON dining.reservation (tenant_id, store_id, starts_at) WHERE (status)::text = 'BOOKED'::text;
DROP INDEX dining.reservation_booked_store_starts_ix;
ALTER INDEX dining.tn_reservation_booked_store_starts_ix RENAME TO reservation_booked_store_starts_ix;
CREATE INDEX tn_reservation_store_starts_ix ON dining.reservation (tenant_id, store_id, starts_at);
DROP INDEX dining.reservation_store_starts_ix;
ALTER INDEX dining.tn_reservation_store_starts_ix RENAME TO reservation_store_starts_ix;
CREATE INDEX tn_table_occupation_open_store_ix ON dining.table_occupation (tenant_id, store_id) WHERE closed_at IS NULL;
DROP INDEX dining.table_occupation_open_store_ix;
ALTER INDEX dining.tn_table_occupation_open_store_ix RENAME TO table_occupation_open_store_ix;

-- (6) The restrictive tenant policy, the tenant_fixed trigger and both registries (the table keeps its store
-- or parent policy of the RLS run: the two are ANDed).
SELECT shared.rls_protect_tenant('dining.room_area');
SELECT shared.rls_protect_tenant('dining.restaurant_table');
SELECT shared.rls_protect_tenant('dining.table_occupation');
SELECT shared.rls_protect_tenant('dining.reservation');
SELECT shared.rls_protect_tenant('dining.reservation_table');
SELECT shared.rls_protect_tenant('dining.dining_settings');
