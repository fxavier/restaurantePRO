-- R6 S74d: the audit trail belongs to one tenant (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- audit_event is a D168 SPECIAL table: its store-scoped read and open insert policies stay as V1106 wrote them, and the
-- restrictive tenant policy is ANDed with them, so a tenant reads only its own events and inserts only its own. No
-- UPDATE runs: the new column takes a constant default (catalog-only, no rewrite) and then the database default
-- shared.rls_tenant_id(), so an insert takes the bound tenant and an unbound one raises RS003. The bootstrap tenant
-- (P70 = none in production) is the fixed literal of every R6 migration and of TenantTestSupport.BOOTSTRAP. The
-- table has no foreign key and no business unique key, so nothing else changes.
ALTER TABLE audit.audit_event
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE audit.audit_event
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE audit.audit_event
    ADD CONSTRAINT audit_event_tenant_id_uk UNIQUE (tenant_id, id);
SELECT shared.rls_protect_tenant('audit.audit_event');

-- D183: a support action is audited as the SUPPORT kind, not as ACCOUNT. The new check admits the same values as
-- V1102's plus SUPPORT; a SUPPORT row names its actor like an ACCOUNT or EMPLOYEE one (V1102's principal check).
-- Re-adding a CHECK scans the table but rewrites no row, so the append-only trigger is not involved.
ALTER TABLE audit.audit_event
    DROP CONSTRAINT audit_event_actor_kind_ck;
ALTER TABLE audit.audit_event
    ADD CONSTRAINT audit_event_actor_kind_ck CHECK (actor_kind IN ('ACCOUNT', 'EMPLOYEE', 'SYSTEM', 'SUPPORT'));

-- The console function (V1105, D143 break-glass) writes with no application session, so the column default would raise
-- RS003: the console names the tenant by setting app.tenant_id for its transaction, and with none set the row belongs
-- to the bootstrap tenant, the only one before the SaaS tenants exist. The signature, grants and the revoke from
-- PUBLIC (V1106) are unchanged: CREATE OR REPLACE keeps them.
CREATE OR REPLACE FUNCTION audit.record_console_event(p_action text, p_entity_type text, p_entity_id text,
                                                      p_reason text)
    RETURNS uuid
    LANGUAGE plpgsql AS
$$
DECLARE
    v_id     uuid := uuidv7();
    v_tenant uuid := coalesce(nullif(current_setting('app.tenant_id', true), '')::uuid,
                              '00000000-0000-7000-8000-000000000001');
BEGIN
    INSERT INTO audit.audit_event (id, tenant_id, occurred_at, actor_id, actor_kind, approver_id, action, entity_type,
                                   entity_id, store_id, terminal_id, reason, details)
    VALUES (v_id, v_tenant, clock_timestamp(), NULL, 'SYSTEM', NULL, p_action, p_entity_type, p_entity_id, NULL, NULL,
            p_reason, '{"via": "console"}'::jsonb);
    RETURN v_id;
END;
$$;
