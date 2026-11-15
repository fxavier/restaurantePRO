-- R6 S74b: settlement becomes tenant-isolated (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- payment_method and order_settlement (UNSCOPED, D167) become TENANT class; the 8 STORE tables and 4 CHILD tables keep
-- their V9060 store / parent policies and gain the restrictive tenant policy. No UPDATE runs: every new column takes a
-- constant default (catalog-only, no rewrite), then the database default shared.rls_tenant_id(), so an insert takes
-- the bound tenant and an unbound one raises RS003. The bootstrap tenant is the fixed literal of every R6 migration
-- (the seeded CASH payment method belongs to it). The append-only guards (V9004, V9006, V9020, V9030, V9040, V9050,
-- V9051) and the deferred money triggers are untouched: no row is rewritten.

-- (1)+(2) tenant_id on every table.
ALTER TABLE settlement.payment_method
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.payment_method
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.order_settlement
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.order_settlement
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.payment_attempt
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.payment_attempt
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.payment
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.payment
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.payment_allocation
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.payment_allocation
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.refund
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.refund
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.bill_partition
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.bill_partition
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.bill_partition_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.bill_partition_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.credit_charge
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.credit_charge
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.reconciliation_batch
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.reconciliation_batch
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.reconciliation_entry
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.reconciliation_entry
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.reconciliation_resolution
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.reconciliation_resolution
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.tax_rounding_adjustment
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.tax_rounding_adjustment
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE settlement.tax_correction_resolution
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE settlement.tax_correction_resolution
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) UNIQUE (tenant_id, id), the target of every composite foreign key below. order_settlement has no id: its
-- primary key leads with the tenant instead (the D51 lock row is per tenant and order).
ALTER TABLE settlement.payment_method
    ADD CONSTRAINT payment_method_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.payment_attempt
    ADD CONSTRAINT payment_attempt_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.payment
    ADD CONSTRAINT payment_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.payment_allocation
    ADD CONSTRAINT payment_allocation_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.refund
    ADD CONSTRAINT refund_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.bill_partition
    ADD CONSTRAINT bill_partition_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.bill_partition_line
    ADD CONSTRAINT bill_partition_line_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.credit_charge
    ADD CONSTRAINT credit_charge_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.reconciliation_batch
    ADD CONSTRAINT reconciliation_batch_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.reconciliation_entry
    ADD CONSTRAINT reconciliation_entry_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.reconciliation_resolution
    ADD CONSTRAINT reconciliation_resolution_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.tax_rounding_adjustment
    ADD CONSTRAINT tax_rounding_adjustment_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.tax_correction_resolution
    ADD CONSTRAINT tax_correction_resolution_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE settlement.order_settlement
    DROP CONSTRAINT order_settlement_pk,
    ADD CONSTRAINT order_settlement_pk PRIMARY KEY (tenant_id, order_id);

-- (4) Business unique keys per tenant: create the (tenant_id, ...) one, drop the old, keep the old name (the
-- application maps a violation by constraint name, UniqueConstraints).
ALTER TABLE settlement.payment_method
    ADD CONSTRAINT payment_method_code_uk_t UNIQUE (tenant_id, code);
ALTER TABLE settlement.payment_method
    DROP CONSTRAINT payment_method_code_uk;
ALTER TABLE settlement.payment_method
    RENAME CONSTRAINT payment_method_code_uk_t TO payment_method_code_uk;
ALTER TABLE settlement.payment_attempt
    ADD CONSTRAINT payment_attempt_provider_reference_uk_t UNIQUE (tenant_id, provider_code, provider_reference);
ALTER TABLE settlement.payment_attempt
    DROP CONSTRAINT payment_attempt_provider_reference_uk;
ALTER TABLE settlement.payment_attempt
    RENAME CONSTRAINT payment_attempt_provider_reference_uk_t TO payment_attempt_provider_reference_uk;
CREATE UNIQUE INDEX payment_attempt_open_per_target_uk_t ON settlement.payment_attempt (tenant_id, target_type, target_key)
    WHERE status IN ('CREATED', 'PENDING', 'UNKNOWN');
DROP INDEX settlement.payment_attempt_open_per_target_uk;
ALTER INDEX settlement.payment_attempt_open_per_target_uk_t RENAME TO payment_attempt_open_per_target_uk;
ALTER TABLE settlement.payment
    ADD CONSTRAINT payment_attempt_uk_t UNIQUE (tenant_id, attempt_id);
ALTER TABLE settlement.payment
    DROP CONSTRAINT payment_attempt_uk;
ALTER TABLE settlement.payment
    RENAME CONSTRAINT payment_attempt_uk_t TO payment_attempt_uk;
ALTER TABLE settlement.payment_allocation
    ADD CONSTRAINT payment_allocation_target_uk_t UNIQUE (tenant_id, payment_id, target_type, target_id);
ALTER TABLE settlement.payment_allocation
    DROP CONSTRAINT payment_allocation_target_uk;
ALTER TABLE settlement.payment_allocation
    RENAME CONSTRAINT payment_allocation_target_uk_t TO payment_allocation_target_uk;
ALTER TABLE settlement.bill_partition
    ADD CONSTRAINT bill_partition_group_part_uk_t UNIQUE (tenant_id, group_id, part_no);
ALTER TABLE settlement.bill_partition
    DROP CONSTRAINT bill_partition_group_part_uk;
ALTER TABLE settlement.bill_partition
    RENAME CONSTRAINT bill_partition_group_part_uk_t TO bill_partition_group_part_uk;
ALTER TABLE settlement.bill_partition_line
    ADD CONSTRAINT bill_partition_line_uk_t UNIQUE (tenant_id, partition_id, order_line_id);
ALTER TABLE settlement.bill_partition_line
    DROP CONSTRAINT bill_partition_line_uk;
ALTER TABLE settlement.bill_partition_line
    RENAME CONSTRAINT bill_partition_line_uk_t TO bill_partition_line_uk;
CREATE UNIQUE INDEX credit_charge_live_per_target_uk_t ON settlement.credit_charge (tenant_id, target_type, target_key)
    WHERE status IN ('RESERVED', 'POSTED');
DROP INDEX settlement.credit_charge_live_per_target_uk;
ALTER INDEX settlement.credit_charge_live_per_target_uk_t RENAME TO credit_charge_live_per_target_uk;
ALTER TABLE settlement.reconciliation_batch
    ADD CONSTRAINT reconciliation_batch_file_uk_t UNIQUE (tenant_id, method_code, file_sha256);
ALTER TABLE settlement.reconciliation_batch
    DROP CONSTRAINT reconciliation_batch_file_uk;
ALTER TABLE settlement.reconciliation_batch
    RENAME CONSTRAINT reconciliation_batch_file_uk_t TO reconciliation_batch_file_uk;
ALTER TABLE settlement.reconciliation_entry
    ADD CONSTRAINT reconciliation_entry_line_uk_t UNIQUE (tenant_id, batch_id, line_no);
ALTER TABLE settlement.reconciliation_entry
    DROP CONSTRAINT reconciliation_entry_line_uk;
ALTER TABLE settlement.reconciliation_entry
    RENAME CONSTRAINT reconciliation_entry_line_uk_t TO reconciliation_entry_line_uk;
CREATE UNIQUE INDEX reconciliation_entry_payment_uk_t ON settlement.reconciliation_entry (tenant_id, payment_id)
    WHERE kind IN ('MATCHED', 'AMOUNT_MISMATCH');
DROP INDEX settlement.reconciliation_entry_payment_uk;
ALTER INDEX settlement.reconciliation_entry_payment_uk_t RENAME TO reconciliation_entry_payment_uk;
ALTER TABLE settlement.reconciliation_resolution
    ADD CONSTRAINT reconciliation_resolution_entry_uk_t UNIQUE (tenant_id, entry_id);
ALTER TABLE settlement.reconciliation_resolution
    DROP CONSTRAINT reconciliation_resolution_entry_uk;
ALTER TABLE settlement.reconciliation_resolution
    RENAME CONSTRAINT reconciliation_resolution_entry_uk_t TO reconciliation_resolution_entry_uk;
ALTER TABLE settlement.tax_rounding_adjustment
    ADD CONSTRAINT tax_rounding_adjustment_line_uk_t UNIQUE (tenant_id, order_id, source_line_id);
ALTER TABLE settlement.tax_rounding_adjustment
    DROP CONSTRAINT tax_rounding_adjustment_line_uk;
ALTER TABLE settlement.tax_rounding_adjustment
    RENAME CONSTRAINT tax_rounding_adjustment_line_uk_t TO tax_rounding_adjustment_line_uk;
ALTER TABLE settlement.tax_correction_resolution
    ADD CONSTRAINT tax_correction_resolution_adjustment_uk_t UNIQUE (tenant_id, adjustment_id);
ALTER TABLE settlement.tax_correction_resolution
    DROP CONSTRAINT tax_correction_resolution_adjustment_uk;
ALTER TABLE settlement.tax_correction_resolution
    RENAME CONSTRAINT tax_correction_resolution_adjustment_uk_t TO tax_correction_resolution_adjustment_uk;

-- (5) The in-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, then drop the
-- single-column one and keep its name. A nullable column (partition_id, payment_id) is still unchecked when null.
ALTER TABLE settlement.payment
    ADD CONSTRAINT payment_attempt_fk_t FOREIGN KEY (tenant_id, attempt_id)
        REFERENCES settlement.payment_attempt (tenant_id, id) NOT VALID;
ALTER TABLE settlement.payment
    VALIDATE CONSTRAINT payment_attempt_fk_t;
ALTER TABLE settlement.payment
    DROP CONSTRAINT payment_attempt_fk;
ALTER TABLE settlement.payment
    RENAME CONSTRAINT payment_attempt_fk_t TO payment_attempt_fk;
ALTER TABLE settlement.payment_allocation
    ADD CONSTRAINT payment_allocation_payment_fk_t FOREIGN KEY (tenant_id, payment_id)
        REFERENCES settlement.payment (tenant_id, id) NOT VALID;
ALTER TABLE settlement.payment_allocation
    VALIDATE CONSTRAINT payment_allocation_payment_fk_t;
ALTER TABLE settlement.payment_allocation
    DROP CONSTRAINT payment_allocation_payment_fk;
ALTER TABLE settlement.payment_allocation
    RENAME CONSTRAINT payment_allocation_payment_fk_t TO payment_allocation_payment_fk;
ALTER TABLE settlement.bill_partition_line
    ADD CONSTRAINT bill_partition_line_partition_fk_t FOREIGN KEY (tenant_id, partition_id)
        REFERENCES settlement.bill_partition (tenant_id, id) NOT VALID;
ALTER TABLE settlement.bill_partition_line
    VALIDATE CONSTRAINT bill_partition_line_partition_fk_t;
ALTER TABLE settlement.bill_partition_line
    DROP CONSTRAINT bill_partition_line_partition_fk;
ALTER TABLE settlement.bill_partition_line
    RENAME CONSTRAINT bill_partition_line_partition_fk_t TO bill_partition_line_partition_fk;
ALTER TABLE settlement.credit_charge
    ADD CONSTRAINT credit_charge_partition_fk_t FOREIGN KEY (tenant_id, partition_id)
        REFERENCES settlement.bill_partition (tenant_id, id) NOT VALID;
ALTER TABLE settlement.credit_charge
    VALIDATE CONSTRAINT credit_charge_partition_fk_t;
ALTER TABLE settlement.credit_charge
    DROP CONSTRAINT credit_charge_partition_fk;
ALTER TABLE settlement.credit_charge
    RENAME CONSTRAINT credit_charge_partition_fk_t TO credit_charge_partition_fk;
ALTER TABLE settlement.refund
    ADD CONSTRAINT refund_payment_fk_t FOREIGN KEY (tenant_id, payment_id)
        REFERENCES settlement.payment (tenant_id, id) NOT VALID;
ALTER TABLE settlement.refund
    VALIDATE CONSTRAINT refund_payment_fk_t;
ALTER TABLE settlement.refund
    DROP CONSTRAINT refund_payment_fk;
ALTER TABLE settlement.refund
    RENAME CONSTRAINT refund_payment_fk_t TO refund_payment_fk;
ALTER TABLE settlement.reconciliation_entry
    ADD CONSTRAINT reconciliation_entry_batch_fk_t FOREIGN KEY (tenant_id, batch_id)
        REFERENCES settlement.reconciliation_batch (tenant_id, id) NOT VALID;
ALTER TABLE settlement.reconciliation_entry
    VALIDATE CONSTRAINT reconciliation_entry_batch_fk_t;
ALTER TABLE settlement.reconciliation_entry
    DROP CONSTRAINT reconciliation_entry_batch_fk;
ALTER TABLE settlement.reconciliation_entry
    RENAME CONSTRAINT reconciliation_entry_batch_fk_t TO reconciliation_entry_batch_fk;
ALTER TABLE settlement.reconciliation_entry
    ADD CONSTRAINT reconciliation_entry_payment_fk_t FOREIGN KEY (tenant_id, payment_id)
        REFERENCES settlement.payment (tenant_id, id) NOT VALID;
ALTER TABLE settlement.reconciliation_entry
    VALIDATE CONSTRAINT reconciliation_entry_payment_fk_t;
ALTER TABLE settlement.reconciliation_entry
    DROP CONSTRAINT reconciliation_entry_payment_fk;
ALTER TABLE settlement.reconciliation_entry
    RENAME CONSTRAINT reconciliation_entry_payment_fk_t TO reconciliation_entry_payment_fk;
ALTER TABLE settlement.reconciliation_resolution
    ADD CONSTRAINT reconciliation_resolution_entry_fk_t FOREIGN KEY (tenant_id, entry_id)
        REFERENCES settlement.reconciliation_entry (tenant_id, id) NOT VALID;
ALTER TABLE settlement.reconciliation_resolution
    VALIDATE CONSTRAINT reconciliation_resolution_entry_fk_t;
ALTER TABLE settlement.reconciliation_resolution
    DROP CONSTRAINT reconciliation_resolution_entry_fk;
ALTER TABLE settlement.reconciliation_resolution
    RENAME CONSTRAINT reconciliation_resolution_entry_fk_t TO reconciliation_resolution_entry_fk;
ALTER TABLE settlement.tax_correction_resolution
    ADD CONSTRAINT tax_correction_resolution_adjustment_fk_t FOREIGN KEY (tenant_id, adjustment_id)
        REFERENCES settlement.tax_rounding_adjustment (tenant_id, id) NOT VALID;
ALTER TABLE settlement.tax_correction_resolution
    VALIDATE CONSTRAINT tax_correction_resolution_adjustment_fk_t;
ALTER TABLE settlement.tax_correction_resolution
    DROP CONSTRAINT tax_correction_resolution_adjustment_fk;
ALTER TABLE settlement.tax_correction_resolution
    RENAME CONSTRAINT tax_correction_resolution_adjustment_fk_t TO tax_correction_resolution_adjustment_fk;

-- (6) The tenant policy through the helper, then the registries. The STORE and CHILD tables already carry their
-- V9060 store / parent policy; the tenant policy is restrictive and ANDs with it. The two UNSCOPED tables leave that
-- registry for TENANT class (a permissive tenant_rows lets the tenant's rows through).
SELECT shared.rls_protect_tenant('settlement.payment_attempt');
SELECT shared.rls_protect_tenant('settlement.payment');
SELECT shared.rls_protect_tenant('settlement.payment_allocation');
SELECT shared.rls_protect_tenant('settlement.refund');
SELECT shared.rls_protect_tenant('settlement.bill_partition');
SELECT shared.rls_protect_tenant('settlement.bill_partition_line');
SELECT shared.rls_protect_tenant('settlement.credit_charge');
SELECT shared.rls_protect_tenant('settlement.reconciliation_batch');
SELECT shared.rls_protect_tenant('settlement.reconciliation_entry');
SELECT shared.rls_protect_tenant('settlement.reconciliation_resolution');
SELECT shared.rls_protect_tenant('settlement.tax_rounding_adjustment');
SELECT shared.rls_protect_tenant('settlement.tax_correction_resolution');
DELETE FROM shared.rls_unscoped_table
WHERE table_name IN ('settlement.payment_method'::regclass, 'settlement.order_settlement'::regclass);
SELECT shared.rls_protect_tenant_table('settlement.payment_method');
SELECT shared.rls_protect_tenant_table('settlement.order_settlement');
