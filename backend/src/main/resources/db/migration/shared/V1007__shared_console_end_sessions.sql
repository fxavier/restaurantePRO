-- Ends every server-side session of a BackOffice account from the server console (D143 break-glass, S56 review):
-- the spring_session tables are shared's (V1002), so only a shared function deletes from them (D6). The principal
-- name is the one identity indexes BackOffice sessions under (SessionPrincipals, D38: 'ACCOUNT:<account id>'); the
-- attribute rows go with their session (ON DELETE CASCADE). Returns how many sessions ended. Called by
-- deploy/mfa-break-glass.sh in the same transaction as identity.mfa_break_glass_reset.
CREATE FUNCTION shared.end_account_sessions(p_account_id uuid) RETURNS integer
    LANGUAGE plpgsql AS
$$
DECLARE
    v_ended integer;
BEGIN
    DELETE FROM shared.spring_session WHERE principal_name = 'ACCOUNT:' || p_account_id::text;
    GET DIAGNOSTICS v_ended = ROW_COUNT;
    RETURN v_ended;
END;
$$;
REVOKE EXECUTE ON FUNCTION shared.end_account_sessions(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION shared.end_account_sessions(uuid) TO CURRENT_USER;
