-- An audit row for an action taken at the server console, outside the application (D143 break-glass, written by
-- S56 in audit's own block). The actor is the system (actor_id NULL, actor_kind SYSTEM) and details say where it came
-- from. deploy/mfa-break-glass.sh calls it in the same transaction as identity.mfa_break_glass_reset.
CREATE FUNCTION audit.record_console_event(p_action text, p_entity_type text, p_entity_id text, p_reason text)
    RETURNS uuid
    LANGUAGE plpgsql AS
$$
DECLARE
    v_id uuid := uuidv7();
BEGIN
    INSERT INTO audit.audit_event (id, occurred_at, actor_id, actor_kind, approver_id, action, entity_type, entity_id,
                                   store_id, terminal_id, reason, details)
    VALUES (v_id, clock_timestamp(), NULL, 'SYSTEM', NULL, p_action, p_entity_type, p_entity_id, NULL, NULL,
            p_reason, '{"via": "console"}'::jsonb);
    RETURN v_id;
END;
$$;
-- A console function only: not callable by every database role (S56 review); see identity.mfa_break_glass_reset.
REVOKE EXECUTE ON FUNCTION audit.record_console_event(text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.record_console_event(text, text, text, text) TO CURRENT_USER;
