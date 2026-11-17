-- R6 S74a: orders becomes tenant-isolated (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- The five STORE tables and the seven CHILD tables of the RLS run gain the restrictive tenant policy on top: their
-- store and parent policies stay. No UPDATE runs: the confirmed facts (lines, voids, transfers, components,
-- adjustments, splits, delivery history) are immutable, and every new column takes a constant default
-- (catalog-only, no rewrite). delivery has no id: its primary key leads with tenant_id, and delivery_event's foreign
-- key to it is rebuilt after the swap.

-- (1)+(2) tenant_id on every table: a constant default first (catalog-only, no rewrite, no UPDATE), then the
-- database default, so an insert takes the bound tenant and an unbound one raises RS003.
ALTER TABLE orders.order_header
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.order_header
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.order_batch
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.order_batch
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.order_transfer
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.order_transfer
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.order_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.order_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.order_line_void
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.order_line_void
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.order_transfer_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.order_transfer_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.order_line_component
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.order_line_component
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.order_line_price_adjustment
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.order_line_price_adjustment
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.order_line_split
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.order_line_split
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.delivery_zone
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.delivery_zone
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.delivery
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.delivery
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE orders.delivery_event
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE orders.delivery_event
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) Identity includes the tenant: a primary key that leads with tenant_id where the table has no id, and
-- UNIQUE (tenant_id, id) on every table with one (the target of composite foreign keys).
ALTER TABLE orders.delivery_event
    DROP CONSTRAINT delivery_event_delivery_fk;
ALTER TABLE orders.delivery
    DROP CONSTRAINT delivery_pk,
    ADD CONSTRAINT delivery_pk PRIMARY KEY (tenant_id, order_id);
ALTER TABLE orders.order_header
    ADD CONSTRAINT order_header_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE orders.order_batch
    ADD CONSTRAINT order_batch_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE orders.order_transfer
    ADD CONSTRAINT order_transfer_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE orders.order_line
    ADD CONSTRAINT order_line_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE orders.order_line_void
    ADD CONSTRAINT order_line_void_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE orders.order_transfer_line
    ADD CONSTRAINT order_transfer_line_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE orders.order_line_component
    ADD CONSTRAINT order_line_component_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE orders.order_line_price_adjustment
    ADD CONSTRAINT order_line_price_adjustment_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE orders.order_line_split
    ADD CONSTRAINT order_line_split_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE orders.delivery_zone
    ADD CONSTRAINT delivery_zone_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE orders.delivery_event
    ADD CONSTRAINT delivery_event_tenant_id_uk UNIQUE (tenant_id, id);

-- (4) Business and reference keys per tenant: the (tenant_id, ...) key is created under a temporary name, the old
-- one dropped after the foreign keys that used it, and the old name restored (the application maps a violation
-- by constraint name, UniqueConstraints).
ALTER TABLE orders.order_batch
    ADD CONSTRAINT tn_order_batch_id_order_uk UNIQUE (tenant_id, id, order_id);
ALTER TABLE orders.order_batch
    ADD CONSTRAINT tn_order_batch_order_no_uk UNIQUE (tenant_id, order_id, batch_no);
ALTER TABLE orders.order_transfer
    ADD CONSTRAINT tn_order_transfer_id_target_uk UNIQUE (tenant_id, id, target_order_id);
ALTER TABLE orders.order_transfer
    ADD CONSTRAINT tn_order_transfer_id_orders_uk UNIQUE (tenant_id, id, source_order_id, target_order_id);
ALTER TABLE orders.order_line
    ADD CONSTRAINT tn_order_line_id_order_uk UNIQUE (tenant_id, id, order_id);
ALTER TABLE orders.order_transfer_line
    ADD CONSTRAINT tn_order_transfer_line_target_uk UNIQUE (tenant_id, target_line_id);
ALTER TABLE orders.order_line_component
    ADD CONSTRAINT tn_order_line_component_line_position_uk UNIQUE (tenant_id, line_id, "position");
ALTER TABLE orders.order_line_price_adjustment
    ADD CONSTRAINT tn_order_line_price_adjustment_line_seq_uk UNIQUE (tenant_id, line_id, seq);
ALTER TABLE orders.order_line_split
    ADD CONSTRAINT tn_order_line_split_id_order_uk UNIQUE (tenant_id, id, order_id);
ALTER TABLE orders.order_line_split
    ADD CONSTRAINT tn_order_line_split_target_uk UNIQUE (tenant_id, target_line_id);
ALTER TABLE orders.delivery_zone
    ADD CONSTRAINT tn_delivery_zone_store_code_uk UNIQUE (tenant_id, store_id, code);
CREATE UNIQUE INDEX tn_order_header_open_table_uk ON orders.order_header (tenant_id, table_id) WHERE lifecycle::text = 'OPEN'::text;

-- (5) In-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, then the
-- single-column one is dropped and the name kept.
ALTER TABLE orders.order_batch
    ADD CONSTRAINT tn_order_batch_order_fk FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders.order_header (tenant_id, id) NOT VALID;
ALTER TABLE orders.order_batch
    VALIDATE CONSTRAINT tn_order_batch_order_fk;
ALTER TABLE orders.order_transfer
    ADD CONSTRAINT tn_order_transfer_source_fk FOREIGN KEY (tenant_id, source_order_id)
        REFERENCES orders.order_header (tenant_id, id) NOT VALID;
ALTER TABLE orders.order_transfer
    VALIDATE CONSTRAINT tn_order_transfer_source_fk;
ALTER TABLE orders.order_transfer
    ADD CONSTRAINT tn_order_transfer_target_fk FOREIGN KEY (tenant_id, target_order_id)
        REFERENCES orders.order_header (tenant_id, id) NOT VALID;
ALTER TABLE orders.order_transfer
    VALIDATE CONSTRAINT tn_order_transfer_target_fk;
ALTER TABLE orders.order_line
    ADD CONSTRAINT tn_order_line_order_fk FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders.order_header (tenant_id, id) NOT VALID;
ALTER TABLE orders.order_line
    VALIDATE CONSTRAINT tn_order_line_order_fk;
ALTER TABLE orders.order_line
    ADD CONSTRAINT tn_order_line_batch_same_order_fk FOREIGN KEY (tenant_id, batch_id, order_id)
        REFERENCES orders.order_batch (tenant_id, id, order_id) NOT VALID;
ALTER TABLE orders.order_line
    VALIDATE CONSTRAINT tn_order_line_batch_same_order_fk;
ALTER TABLE orders.order_line
    ADD CONSTRAINT tn_order_line_origin_split_same_order_fk FOREIGN KEY (tenant_id, origin_split_id, order_id)
        REFERENCES orders.order_line_split (tenant_id, id, order_id) DEFERRABLE INITIALLY DEFERRED NOT VALID;
ALTER TABLE orders.order_line
    VALIDATE CONSTRAINT tn_order_line_origin_split_same_order_fk;
ALTER TABLE orders.order_line
    ADD CONSTRAINT tn_order_line_origin_transfer_same_order_fk FOREIGN KEY (tenant_id, origin_transfer_id, order_id)
        REFERENCES orders.order_transfer (tenant_id, id, target_order_id) NOT VALID;
ALTER TABLE orders.order_line
    VALIDATE CONSTRAINT tn_order_line_origin_transfer_same_order_fk;
ALTER TABLE orders.order_line_void
    ADD CONSTRAINT tn_order_line_void_order_fk FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders.order_header (tenant_id, id) NOT VALID;
ALTER TABLE orders.order_line_void
    VALIDATE CONSTRAINT tn_order_line_void_order_fk;
ALTER TABLE orders.order_line_void
    ADD CONSTRAINT tn_order_line_void_line_same_order_fk FOREIGN KEY (tenant_id, line_id, order_id)
        REFERENCES orders.order_line (tenant_id, id, order_id) NOT VALID;
ALTER TABLE orders.order_line_void
    VALIDATE CONSTRAINT tn_order_line_void_line_same_order_fk;
ALTER TABLE orders.order_transfer_line
    ADD CONSTRAINT tn_order_transfer_line_source_same_order_fk FOREIGN KEY (tenant_id, source_line_id, source_order_id)
        REFERENCES orders.order_line (tenant_id, id, order_id) NOT VALID;
ALTER TABLE orders.order_transfer_line
    VALIDATE CONSTRAINT tn_order_transfer_line_source_same_order_fk;
ALTER TABLE orders.order_transfer_line
    ADD CONSTRAINT tn_order_transfer_line_target_same_order_fk FOREIGN KEY (tenant_id, target_line_id, target_order_id)
        REFERENCES orders.order_line (tenant_id, id, order_id) NOT VALID;
ALTER TABLE orders.order_transfer_line
    VALIDATE CONSTRAINT tn_order_transfer_line_target_same_order_fk;
ALTER TABLE orders.order_transfer_line
    ADD CONSTRAINT tn_order_transfer_line_transfer_fk FOREIGN KEY (tenant_id, transfer_id)
        REFERENCES orders.order_transfer (tenant_id, id) NOT VALID;
ALTER TABLE orders.order_transfer_line
    VALIDATE CONSTRAINT tn_order_transfer_line_transfer_fk;
ALTER TABLE orders.order_transfer_line
    ADD CONSTRAINT tn_order_transfer_line_transfer_orders_fk FOREIGN KEY (tenant_id, transfer_id, source_order_id, target_order_id)
        REFERENCES orders.order_transfer (tenant_id, id, source_order_id, target_order_id) NOT VALID;
ALTER TABLE orders.order_transfer_line
    VALIDATE CONSTRAINT tn_order_transfer_line_transfer_orders_fk;
ALTER TABLE orders.order_line_component
    ADD CONSTRAINT tn_order_line_component_order_fk FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders.order_header (tenant_id, id) NOT VALID;
ALTER TABLE orders.order_line_component
    VALIDATE CONSTRAINT tn_order_line_component_order_fk;
ALTER TABLE orders.order_line_component
    ADD CONSTRAINT tn_order_line_component_line_same_order_fk FOREIGN KEY (tenant_id, line_id, order_id)
        REFERENCES orders.order_line (tenant_id, id, order_id) NOT VALID;
ALTER TABLE orders.order_line_component
    VALIDATE CONSTRAINT tn_order_line_component_line_same_order_fk;
ALTER TABLE orders.order_line_price_adjustment
    ADD CONSTRAINT tn_order_line_price_adjustment_order_fk FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders.order_header (tenant_id, id) NOT VALID;
ALTER TABLE orders.order_line_price_adjustment
    VALIDATE CONSTRAINT tn_order_line_price_adjustment_order_fk;
ALTER TABLE orders.order_line_price_adjustment
    ADD CONSTRAINT tn_order_line_price_adjustment_line_same_order_fk FOREIGN KEY (tenant_id, line_id, order_id)
        REFERENCES orders.order_line (tenant_id, id, order_id) NOT VALID;
ALTER TABLE orders.order_line_price_adjustment
    VALIDATE CONSTRAINT tn_order_line_price_adjustment_line_same_order_fk;
ALTER TABLE orders.order_line_split
    ADD CONSTRAINT tn_order_line_split_order_fk FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders.order_header (tenant_id, id) NOT VALID;
ALTER TABLE orders.order_line_split
    VALIDATE CONSTRAINT tn_order_line_split_order_fk;
ALTER TABLE orders.order_line_split
    ADD CONSTRAINT tn_order_line_split_adjustment_fk FOREIGN KEY (tenant_id, adjustment_id)
        REFERENCES orders.order_line_price_adjustment (tenant_id, id) DEFERRABLE INITIALLY DEFERRED NOT VALID;
ALTER TABLE orders.order_line_split
    VALIDATE CONSTRAINT tn_order_line_split_adjustment_fk;
ALTER TABLE orders.order_line_split
    ADD CONSTRAINT tn_order_line_split_source_same_order_fk FOREIGN KEY (tenant_id, source_line_id, order_id)
        REFERENCES orders.order_line (tenant_id, id, order_id) NOT VALID;
ALTER TABLE orders.order_line_split
    VALIDATE CONSTRAINT tn_order_line_split_source_same_order_fk;
ALTER TABLE orders.order_line_split
    ADD CONSTRAINT tn_order_line_split_target_same_order_fk FOREIGN KEY (tenant_id, target_line_id, order_id)
        REFERENCES orders.order_line (tenant_id, id, order_id) NOT VALID;
ALTER TABLE orders.order_line_split
    VALIDATE CONSTRAINT tn_order_line_split_target_same_order_fk;
ALTER TABLE orders.delivery
    ADD CONSTRAINT tn_delivery_order_fk FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders.order_header (tenant_id, id) NOT VALID;
ALTER TABLE orders.delivery
    VALIDATE CONSTRAINT tn_delivery_order_fk;
ALTER TABLE orders.delivery
    ADD CONSTRAINT tn_delivery_zone_fk FOREIGN KEY (tenant_id, zone_id)
        REFERENCES orders.delivery_zone (tenant_id, id) NOT VALID;
ALTER TABLE orders.delivery
    VALIDATE CONSTRAINT tn_delivery_zone_fk;
ALTER TABLE orders.delivery_event
    ADD CONSTRAINT tn_delivery_event_delivery_fk FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders.delivery (tenant_id, order_id) NOT VALID;
ALTER TABLE orders.delivery_event
    VALIDATE CONSTRAINT tn_delivery_event_delivery_fk;
ALTER TABLE orders.order_batch
    DROP CONSTRAINT order_batch_order_fk;
ALTER TABLE orders.order_transfer
    DROP CONSTRAINT order_transfer_source_fk;
ALTER TABLE orders.order_transfer
    DROP CONSTRAINT order_transfer_target_fk;
ALTER TABLE orders.order_line
    DROP CONSTRAINT order_line_order_fk;
ALTER TABLE orders.order_line
    DROP CONSTRAINT order_line_batch_same_order_fk;
ALTER TABLE orders.order_line
    DROP CONSTRAINT order_line_origin_split_same_order_fk;
ALTER TABLE orders.order_line
    DROP CONSTRAINT order_line_origin_transfer_same_order_fk;
ALTER TABLE orders.order_line_void
    DROP CONSTRAINT order_line_void_order_fk;
ALTER TABLE orders.order_line_void
    DROP CONSTRAINT order_line_void_line_same_order_fk;
ALTER TABLE orders.order_transfer_line
    DROP CONSTRAINT order_transfer_line_source_same_order_fk;
ALTER TABLE orders.order_transfer_line
    DROP CONSTRAINT order_transfer_line_target_same_order_fk;
ALTER TABLE orders.order_transfer_line
    DROP CONSTRAINT order_transfer_line_transfer_fk;
ALTER TABLE orders.order_transfer_line
    DROP CONSTRAINT order_transfer_line_transfer_orders_fk;
ALTER TABLE orders.order_line_component
    DROP CONSTRAINT order_line_component_order_fk;
ALTER TABLE orders.order_line_component
    DROP CONSTRAINT order_line_component_line_same_order_fk;
ALTER TABLE orders.order_line_price_adjustment
    DROP CONSTRAINT order_line_price_adjustment_order_fk;
ALTER TABLE orders.order_line_price_adjustment
    DROP CONSTRAINT order_line_price_adjustment_line_same_order_fk;
ALTER TABLE orders.order_line_split
    DROP CONSTRAINT order_line_split_order_fk;
ALTER TABLE orders.order_line_split
    DROP CONSTRAINT order_line_split_adjustment_fk;
ALTER TABLE orders.order_line_split
    DROP CONSTRAINT order_line_split_source_same_order_fk;
ALTER TABLE orders.order_line_split
    DROP CONSTRAINT order_line_split_target_same_order_fk;
ALTER TABLE orders.delivery
    DROP CONSTRAINT delivery_order_fk;
ALTER TABLE orders.delivery
    DROP CONSTRAINT delivery_zone_fk;
ALTER TABLE orders.order_batch
    DROP CONSTRAINT order_batch_id_order_uk;
ALTER TABLE orders.order_batch
    DROP CONSTRAINT order_batch_order_no_uk;
ALTER TABLE orders.order_transfer
    DROP CONSTRAINT order_transfer_id_target_uk;
ALTER TABLE orders.order_transfer
    DROP CONSTRAINT order_transfer_id_orders_uk;
ALTER TABLE orders.order_line
    DROP CONSTRAINT order_line_id_order_uk;
ALTER TABLE orders.order_transfer_line
    DROP CONSTRAINT order_transfer_line_target_uk;
ALTER TABLE orders.order_line_component
    DROP CONSTRAINT order_line_component_line_position_uk;
ALTER TABLE orders.order_line_price_adjustment
    DROP CONSTRAINT order_line_price_adjustment_line_seq_uk;
ALTER TABLE orders.order_line_split
    DROP CONSTRAINT order_line_split_id_order_uk;
ALTER TABLE orders.order_line_split
    DROP CONSTRAINT order_line_split_target_uk;
ALTER TABLE orders.delivery_zone
    DROP CONSTRAINT delivery_zone_store_code_uk;
DROP INDEX orders.order_header_open_table_uk;
ALTER TABLE orders.order_batch
    RENAME CONSTRAINT tn_order_batch_id_order_uk TO order_batch_id_order_uk;
ALTER TABLE orders.order_batch
    RENAME CONSTRAINT tn_order_batch_order_no_uk TO order_batch_order_no_uk;
ALTER TABLE orders.order_transfer
    RENAME CONSTRAINT tn_order_transfer_id_target_uk TO order_transfer_id_target_uk;
ALTER TABLE orders.order_transfer
    RENAME CONSTRAINT tn_order_transfer_id_orders_uk TO order_transfer_id_orders_uk;
ALTER TABLE orders.order_line
    RENAME CONSTRAINT tn_order_line_id_order_uk TO order_line_id_order_uk;
ALTER TABLE orders.order_transfer_line
    RENAME CONSTRAINT tn_order_transfer_line_target_uk TO order_transfer_line_target_uk;
ALTER TABLE orders.order_line_component
    RENAME CONSTRAINT tn_order_line_component_line_position_uk TO order_line_component_line_position_uk;
ALTER TABLE orders.order_line_price_adjustment
    RENAME CONSTRAINT tn_order_line_price_adjustment_line_seq_uk TO order_line_price_adjustment_line_seq_uk;
ALTER TABLE orders.order_line_split
    RENAME CONSTRAINT tn_order_line_split_id_order_uk TO order_line_split_id_order_uk;
ALTER TABLE orders.order_line_split
    RENAME CONSTRAINT tn_order_line_split_target_uk TO order_line_split_target_uk;
ALTER TABLE orders.delivery_zone
    RENAME CONSTRAINT tn_delivery_zone_store_code_uk TO delivery_zone_store_code_uk;
ALTER TABLE orders.order_batch
    RENAME CONSTRAINT tn_order_batch_order_fk TO order_batch_order_fk;
ALTER TABLE orders.order_transfer
    RENAME CONSTRAINT tn_order_transfer_source_fk TO order_transfer_source_fk;
ALTER TABLE orders.order_transfer
    RENAME CONSTRAINT tn_order_transfer_target_fk TO order_transfer_target_fk;
ALTER TABLE orders.order_line
    RENAME CONSTRAINT tn_order_line_order_fk TO order_line_order_fk;
ALTER TABLE orders.order_line
    RENAME CONSTRAINT tn_order_line_batch_same_order_fk TO order_line_batch_same_order_fk;
ALTER TABLE orders.order_line
    RENAME CONSTRAINT tn_order_line_origin_split_same_order_fk TO order_line_origin_split_same_order_fk;
ALTER TABLE orders.order_line
    RENAME CONSTRAINT tn_order_line_origin_transfer_same_order_fk TO order_line_origin_transfer_same_order_fk;
ALTER TABLE orders.order_line_void
    RENAME CONSTRAINT tn_order_line_void_order_fk TO order_line_void_order_fk;
ALTER TABLE orders.order_line_void
    RENAME CONSTRAINT tn_order_line_void_line_same_order_fk TO order_line_void_line_same_order_fk;
ALTER TABLE orders.order_transfer_line
    RENAME CONSTRAINT tn_order_transfer_line_source_same_order_fk TO order_transfer_line_source_same_order_fk;
ALTER TABLE orders.order_transfer_line
    RENAME CONSTRAINT tn_order_transfer_line_target_same_order_fk TO order_transfer_line_target_same_order_fk;
ALTER TABLE orders.order_transfer_line
    RENAME CONSTRAINT tn_order_transfer_line_transfer_fk TO order_transfer_line_transfer_fk;
ALTER TABLE orders.order_transfer_line
    RENAME CONSTRAINT tn_order_transfer_line_transfer_orders_fk TO order_transfer_line_transfer_orders_fk;
ALTER TABLE orders.order_line_component
    RENAME CONSTRAINT tn_order_line_component_order_fk TO order_line_component_order_fk;
ALTER TABLE orders.order_line_component
    RENAME CONSTRAINT tn_order_line_component_line_same_order_fk TO order_line_component_line_same_order_fk;
ALTER TABLE orders.order_line_price_adjustment
    RENAME CONSTRAINT tn_order_line_price_adjustment_order_fk TO order_line_price_adjustment_order_fk;
ALTER TABLE orders.order_line_price_adjustment
    RENAME CONSTRAINT tn_order_line_price_adjustment_line_same_order_fk TO order_line_price_adjustment_line_same_order_fk;
ALTER TABLE orders.order_line_split
    RENAME CONSTRAINT tn_order_line_split_order_fk TO order_line_split_order_fk;
ALTER TABLE orders.order_line_split
    RENAME CONSTRAINT tn_order_line_split_adjustment_fk TO order_line_split_adjustment_fk;
ALTER TABLE orders.order_line_split
    RENAME CONSTRAINT tn_order_line_split_source_same_order_fk TO order_line_split_source_same_order_fk;
ALTER TABLE orders.order_line_split
    RENAME CONSTRAINT tn_order_line_split_target_same_order_fk TO order_line_split_target_same_order_fk;
ALTER TABLE orders.delivery
    RENAME CONSTRAINT tn_delivery_order_fk TO delivery_order_fk;
ALTER TABLE orders.delivery
    RENAME CONSTRAINT tn_delivery_zone_fk TO delivery_zone_fk;
ALTER TABLE orders.delivery_event
    RENAME CONSTRAINT tn_delivery_event_delivery_fk TO delivery_event_delivery_fk;
ALTER INDEX orders.tn_order_header_open_table_uk RENAME TO order_header_open_table_uk;

-- D180 Indexes: every composite index that led with store_id now leads with tenant_id (same name and predicate).
CREATE INDEX tn_delivery_store_status_ix ON orders.delivery (tenant_id, store_id, status);
DROP INDEX orders.delivery_store_status_ix;
ALTER INDEX orders.tn_delivery_store_status_ix RENAME TO delivery_store_status_ix;
CREATE INDEX tn_order_header_store_business_day_ix ON orders.order_header (tenant_id, store_id, business_day);
DROP INDEX orders.order_header_store_business_day_ix;
ALTER INDEX orders.tn_order_header_store_business_day_ix RENAME TO order_header_store_business_day_ix;
CREATE INDEX tn_order_header_store_lifecycle_ix ON orders.order_header (tenant_id, store_id, lifecycle);
DROP INDEX orders.order_header_store_lifecycle_ix;
ALTER INDEX orders.tn_order_header_store_lifecycle_ix RENAME TO order_header_store_lifecycle_ix;

-- (6) The restrictive tenant policy, the tenant_fixed trigger and both registries (the table keeps its store
-- or parent policy of the RLS run: the two are ANDed).
SELECT shared.rls_protect_tenant('orders.order_header');
SELECT shared.rls_protect_tenant('orders.order_batch');
SELECT shared.rls_protect_tenant('orders.order_transfer');
SELECT shared.rls_protect_tenant('orders.order_line');
SELECT shared.rls_protect_tenant('orders.order_line_void');
SELECT shared.rls_protect_tenant('orders.order_transfer_line');
SELECT shared.rls_protect_tenant('orders.order_line_component');
SELECT shared.rls_protect_tenant('orders.order_line_price_adjustment');
SELECT shared.rls_protect_tenant('orders.order_line_split');
SELECT shared.rls_protect_tenant('orders.delivery_zone');
SELECT shared.rls_protect_tenant('orders.delivery');
SELECT shared.rls_protect_tenant('orders.delivery_event');
