-- Append-only functional audit (spec §10, D18, D20). Cross-module ids carry no FK (D6).
CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.audit_event
(
    id          UUID                     NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_id    UUID, -- NULL = the system itself (bootstrap, scheduled jobs)
    approver_id UUID,
    action      VARCHAR(100)             NOT NULL,
    entity_type VARCHAR(100)             NOT NULL,
    entity_id   VARCHAR(100)             NOT NULL,
    store_id    UUID,
    terminal_id UUID,
    reason      TEXT,
    details     JSONB                    NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT audit_event_pk PRIMARY KEY (id),
    CONSTRAINT audit_event_action_not_blank_ck CHECK (btrim(action) <> ''),
    CONSTRAINT audit_event_entity_type_not_blank_ck CHECK (btrim(entity_type) <> ''),
    CONSTRAINT audit_event_entity_id_not_blank_ck CHECK (btrim(entity_id) <> '')
);
CREATE INDEX audit_event_entity_ix ON audit.audit_event (entity_type, entity_id, occurred_at);
CREATE INDEX audit_event_store_ix ON audit.audit_event (store_id, occurred_at);
CREATE INDEX audit_event_actor_ix ON audit.audit_event (actor_id, occurred_at);

-- Confirmed facts are immutable (D18): the database itself refuses to change or remove audit rows.
CREATE FUNCTION audit.reject_audit_event_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit.audit_event is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER audit_event_no_update_or_delete
    BEFORE UPDATE OR DELETE
    ON audit.audit_event
    FOR EACH ROW
EXECUTE FUNCTION audit.reject_audit_event_change();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE
    ON audit.audit_event
    FOR EACH STATEMENT
EXECUTE FUNCTION audit.reject_audit_event_change();
