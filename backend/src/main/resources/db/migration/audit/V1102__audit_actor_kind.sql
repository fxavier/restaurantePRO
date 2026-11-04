-- D38, D41: which kind of principal acted. NULL only for rows written before R2; SYSTEM rows name no actor.
-- Adding a nullable column and CHECK constraints rewrites no row, so the append-only trigger is not involved.
ALTER TABLE audit.audit_event
    ADD COLUMN actor_kind VARCHAR(10);

ALTER TABLE audit.audit_event
    ADD CONSTRAINT audit_event_actor_kind_ck CHECK (actor_kind IN ('ACCOUNT', 'EMPLOYEE', 'SYSTEM')),
    ADD CONSTRAINT audit_event_system_has_no_actor_ck CHECK (actor_kind <> 'SYSTEM' OR actor_id IS NULL),
    ADD CONSTRAINT audit_event_principal_has_actor_ck CHECK (actor_kind = 'SYSTEM' OR actor_kind IS NULL
        OR actor_id IS NOT NULL);
