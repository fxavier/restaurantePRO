-- R6 S74b: cash becomes tenant-isolated (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- The 6 STORE tables and 2 CHILD tables keep their V8011 store / parent policies and gain the restrictive tenant
-- policy. No UPDATE runs: every new column takes a constant default (catalog-only, no rewrite), then the database
-- default shared.rls_tenant_id(). The store-consistency keys (id, store_id) and (id, cash_day_id, register_id,
-- store_id) that the composite foreign keys use now lead with the tenant too. The guards of V8004-V8009 are untouched.

-- (1)+(2) tenant_id on every table.
ALTER TABLE cash.cash_register
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE cash.cash_register
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE cash.cash_day
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE cash.cash_day
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE cash.cash_day_reopening
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE cash.cash_day_reopening
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE cash.cash_session
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE cash.cash_session
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE cash.cash_session_count
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE cash.cash_session_count
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE cash.cash_movement
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE cash.cash_movement
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE cash.cash_closing
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE cash.cash_closing
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE cash.cash_closing_line
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE cash.cash_closing_line
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) UNIQUE (tenant_id, id), and the tenant-led targets of the store-consistent foreign keys.
ALTER TABLE cash.cash_register
    ADD CONSTRAINT cash_register_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE cash.cash_day
    ADD CONSTRAINT cash_day_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE cash.cash_day_reopening
    ADD CONSTRAINT cash_day_reopening_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE cash.cash_session
    ADD CONSTRAINT cash_session_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE cash.cash_session_count
    ADD CONSTRAINT cash_session_count_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE cash.cash_movement
    ADD CONSTRAINT cash_movement_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE cash.cash_closing
    ADD CONSTRAINT cash_closing_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE cash.cash_closing_line
    ADD CONSTRAINT cash_closing_line_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE cash.cash_register
    ADD CONSTRAINT cash_register_tenant_id_store_t UNIQUE (tenant_id, id, store_id);
ALTER TABLE cash.cash_day
    ADD CONSTRAINT cash_day_tenant_id_store_t UNIQUE (tenant_id, id, store_id);
ALTER TABLE cash.cash_session
    ADD CONSTRAINT cash_session_tenant_scope_t UNIQUE (tenant_id, id, cash_day_id, register_id, store_id);

-- (5) The in-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, then drop the
-- single-column one and keep its name. They come before the old (id, store_id) keys are dropped.
ALTER TABLE cash.cash_day_reopening
    ADD CONSTRAINT cash_day_reopening_day_fk_t FOREIGN KEY (tenant_id, cash_day_id, store_id)
        REFERENCES cash.cash_day (tenant_id, id, store_id) NOT VALID;
ALTER TABLE cash.cash_day_reopening
    VALIDATE CONSTRAINT cash_day_reopening_day_fk_t;
ALTER TABLE cash.cash_day_reopening
    DROP CONSTRAINT cash_day_reopening_day_fk;
ALTER TABLE cash.cash_day_reopening
    RENAME CONSTRAINT cash_day_reopening_day_fk_t TO cash_day_reopening_day_fk;
ALTER TABLE cash.cash_session
    ADD CONSTRAINT cash_session_register_fk_t FOREIGN KEY (tenant_id, register_id, store_id)
        REFERENCES cash.cash_register (tenant_id, id, store_id) NOT VALID;
ALTER TABLE cash.cash_session
    VALIDATE CONSTRAINT cash_session_register_fk_t;
ALTER TABLE cash.cash_session
    DROP CONSTRAINT cash_session_register_fk;
ALTER TABLE cash.cash_session
    RENAME CONSTRAINT cash_session_register_fk_t TO cash_session_register_fk;
ALTER TABLE cash.cash_session
    ADD CONSTRAINT cash_session_day_fk_t FOREIGN KEY (tenant_id, cash_day_id, store_id)
        REFERENCES cash.cash_day (tenant_id, id, store_id) NOT VALID;
ALTER TABLE cash.cash_session
    VALIDATE CONSTRAINT cash_session_day_fk_t;
ALTER TABLE cash.cash_session
    DROP CONSTRAINT cash_session_day_fk;
ALTER TABLE cash.cash_session
    RENAME CONSTRAINT cash_session_day_fk_t TO cash_session_day_fk;
ALTER TABLE cash.cash_session_count
    ADD CONSTRAINT cash_session_count_session_fk_t FOREIGN KEY (tenant_id, session_id)
        REFERENCES cash.cash_session (tenant_id, id) NOT VALID;
ALTER TABLE cash.cash_session_count
    VALIDATE CONSTRAINT cash_session_count_session_fk_t;
ALTER TABLE cash.cash_session_count
    DROP CONSTRAINT cash_session_count_session_fk;
ALTER TABLE cash.cash_session_count
    RENAME CONSTRAINT cash_session_count_session_fk_t TO cash_session_count_session_fk;
ALTER TABLE cash.cash_movement
    ADD CONSTRAINT cash_movement_session_fk_t FOREIGN KEY (tenant_id, session_id, cash_day_id, register_id, store_id)
        REFERENCES cash.cash_session (tenant_id, id, cash_day_id, register_id, store_id) NOT VALID;
ALTER TABLE cash.cash_movement
    VALIDATE CONSTRAINT cash_movement_session_fk_t;
ALTER TABLE cash.cash_movement
    DROP CONSTRAINT cash_movement_session_fk;
ALTER TABLE cash.cash_movement
    RENAME CONSTRAINT cash_movement_session_fk_t TO cash_movement_session_fk;
ALTER TABLE cash.cash_closing
    ADD CONSTRAINT cash_closing_register_fk_t FOREIGN KEY (tenant_id, register_id, store_id)
        REFERENCES cash.cash_register (tenant_id, id, store_id) NOT VALID;
ALTER TABLE cash.cash_closing
    VALIDATE CONSTRAINT cash_closing_register_fk_t;
ALTER TABLE cash.cash_closing
    DROP CONSTRAINT cash_closing_register_fk;
ALTER TABLE cash.cash_closing
    RENAME CONSTRAINT cash_closing_register_fk_t TO cash_closing_register_fk;
ALTER TABLE cash.cash_closing
    ADD CONSTRAINT cash_closing_day_fk_t FOREIGN KEY (tenant_id, cash_day_id, store_id)
        REFERENCES cash.cash_day (tenant_id, id, store_id) NOT VALID;
ALTER TABLE cash.cash_closing
    VALIDATE CONSTRAINT cash_closing_day_fk_t;
ALTER TABLE cash.cash_closing
    DROP CONSTRAINT cash_closing_day_fk;
ALTER TABLE cash.cash_closing
    RENAME CONSTRAINT cash_closing_day_fk_t TO cash_closing_day_fk;
ALTER TABLE cash.cash_closing
    ADD CONSTRAINT cash_closing_supersedes_fk_t FOREIGN KEY (tenant_id, supersedes_id)
        REFERENCES cash.cash_closing (tenant_id, id) NOT VALID;
ALTER TABLE cash.cash_closing
    VALIDATE CONSTRAINT cash_closing_supersedes_fk_t;
ALTER TABLE cash.cash_closing
    DROP CONSTRAINT cash_closing_supersedes_fk;
ALTER TABLE cash.cash_closing
    RENAME CONSTRAINT cash_closing_supersedes_fk_t TO cash_closing_supersedes_fk;
ALTER TABLE cash.cash_closing_line
    ADD CONSTRAINT cash_closing_line_closing_fk_t FOREIGN KEY (tenant_id, closing_id)
        REFERENCES cash.cash_closing (tenant_id, id) NOT VALID;
ALTER TABLE cash.cash_closing_line
    VALIDATE CONSTRAINT cash_closing_line_closing_fk_t;
ALTER TABLE cash.cash_closing_line
    DROP CONSTRAINT cash_closing_line_closing_fk;
ALTER TABLE cash.cash_closing_line
    RENAME CONSTRAINT cash_closing_line_closing_fk_t TO cash_closing_line_closing_fk;

-- The old store-consistency keys are no longer referenced: drop them and keep their names on the tenant-led ones.
ALTER TABLE cash.cash_register
    DROP CONSTRAINT cash_register_id_store_uk;
ALTER TABLE cash.cash_register
    RENAME CONSTRAINT cash_register_tenant_id_store_t TO cash_register_id_store_uk;
ALTER TABLE cash.cash_day
    DROP CONSTRAINT cash_day_id_store_uk;
ALTER TABLE cash.cash_day
    RENAME CONSTRAINT cash_day_tenant_id_store_t TO cash_day_id_store_uk;
ALTER TABLE cash.cash_session
    DROP CONSTRAINT cash_session_scope_uk;
ALTER TABLE cash.cash_session
    RENAME CONSTRAINT cash_session_tenant_scope_t TO cash_session_scope_uk;

-- (4) Business unique keys per tenant: create the (tenant_id, ...) one, drop the old, keep the old name (the
-- application maps a violation by constraint name, UniqueConstraints).
ALTER TABLE cash.cash_register
    ADD CONSTRAINT cash_register_store_code_uk_t UNIQUE (tenant_id, store_id, code);
ALTER TABLE cash.cash_register
    DROP CONSTRAINT cash_register_store_code_uk;
ALTER TABLE cash.cash_register
    RENAME CONSTRAINT cash_register_store_code_uk_t TO cash_register_store_code_uk;
ALTER TABLE cash.cash_day
    ADD CONSTRAINT cash_day_store_date_uk_t UNIQUE (tenant_id, store_id, business_date);
ALTER TABLE cash.cash_day
    DROP CONSTRAINT cash_day_store_date_uk;
ALTER TABLE cash.cash_day
    RENAME CONSTRAINT cash_day_store_date_uk_t TO cash_day_store_date_uk;
CREATE UNIQUE INDEX cash_session_register_open_uk_t ON cash.cash_session (tenant_id, register_id)
    WHERE status = 'OPEN';
DROP INDEX cash.cash_session_register_open_uk;
ALTER INDEX cash.cash_session_register_open_uk_t RENAME TO cash_session_register_open_uk;
CREATE UNIQUE INDEX cash_session_operator_open_uk_t ON cash.cash_session (tenant_id, store_id, operator_employee_id)
    WHERE status = 'OPEN';
DROP INDEX cash.cash_session_operator_open_uk;
ALTER INDEX cash.cash_session_operator_open_uk_t RENAME TO cash_session_operator_open_uk;
ALTER TABLE cash.cash_session_count
    ADD CONSTRAINT cash_session_count_method_uk_t UNIQUE (tenant_id, session_id, method_code);
ALTER TABLE cash.cash_session_count
    DROP CONSTRAINT cash_session_count_method_uk;
ALTER TABLE cash.cash_session_count
    RENAME CONSTRAINT cash_session_count_method_uk_t TO cash_session_count_method_uk;
CREATE UNIQUE INDEX cash_movement_source_uk_t ON cash.cash_movement (tenant_id, source_type, source_id)
    WHERE source_id IS NOT NULL;
DROP INDEX cash.cash_movement_source_uk;
ALTER INDEX cash.cash_movement_source_uk_t RENAME TO cash_movement_source_uk;
ALTER TABLE cash.cash_closing
    ADD CONSTRAINT cash_closing_attempt_uk_t UNIQUE (tenant_id, register_id, cash_day_id, day_round, attempt_no);
ALTER TABLE cash.cash_closing
    DROP CONSTRAINT cash_closing_attempt_uk;
ALTER TABLE cash.cash_closing
    RENAME CONSTRAINT cash_closing_attempt_uk_t TO cash_closing_attempt_uk;
CREATE UNIQUE INDEX cash_closing_live_uk_t ON cash.cash_closing (tenant_id, register_id, cash_day_id, day_round)
    WHERE status IN ('DRAFT', 'SUBMITTED', 'APPROVED');
DROP INDEX cash.cash_closing_live_uk;
ALTER INDEX cash.cash_closing_live_uk_t RENAME TO cash_closing_live_uk;
ALTER TABLE cash.cash_closing_line
    ADD CONSTRAINT cash_closing_line_method_uk_t UNIQUE (tenant_id, closing_id, method_code);
ALTER TABLE cash.cash_closing_line
    DROP CONSTRAINT cash_closing_line_method_uk;
ALTER TABLE cash.cash_closing_line
    RENAME CONSTRAINT cash_closing_line_method_uk_t TO cash_closing_line_method_uk;

-- (6) The tenant policy through the helper, then the registries; the V8011 store / parent policies stay.
SELECT shared.rls_protect_tenant('cash.cash_register');
SELECT shared.rls_protect_tenant('cash.cash_day');
SELECT shared.rls_protect_tenant('cash.cash_day_reopening');
SELECT shared.rls_protect_tenant('cash.cash_session');
SELECT shared.rls_protect_tenant('cash.cash_session_count');
SELECT shared.rls_protect_tenant('cash.cash_movement');
SELECT shared.rls_protect_tenant('cash.cash_closing');
SELECT shared.rls_protect_tenant('cash.cash_closing_line');
