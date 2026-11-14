-- Row-level security on the audit trail (ADR 0039, D167, D168 special). Reads are scoped with the canonical
-- VIS(store_id), so a store-less row (system, company) is read only by an all-stores scope, as D82 does in the
-- application. Inserts are open: a login, a logout or a lockout is audited before any actor (and so any scope) exists
-- (D20, D41). No UPDATE or DELETE policy: the pool can change no row (the D18 trigger refuses it for every role).
-- No row is written, so the append-only trigger is not involved.
ALTER TABLE audit.audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_event FORCE ROW LEVEL SECURITY;
DO
$$
BEGIN
    EXECUTE format('CREATE POLICY audit_read ON audit.audit_event AS PERMISSIVE FOR SELECT TO PUBLIC USING (%s)',
                   shared.rls_visible('store_id'));
END;
$$;
CREATE POLICY audit_insert ON audit.audit_event AS PERMISSIVE FOR INSERT TO PUBLIC WITH CHECK (true);
SELECT shared.rls_register_protected('audit.audit_event');

-- A console function only (D168): the superuser console calls it, never the pool. V1105 revoked it already; this
-- restates it with the policies, so the two travel together.
REVOKE EXECUTE ON FUNCTION audit.record_console_event(text, text, text, text) FROM PUBLIC;
