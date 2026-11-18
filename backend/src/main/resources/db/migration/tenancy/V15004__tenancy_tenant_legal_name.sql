-- FE-F08: the bound tenant's legal name for GET /auth/session (the name GET /auth/tenants lists), so the BackOffice
-- shows the company the request was resolved in, never one guessed by the client. Same rules as tenant_status
-- (V15001): the bound tenant only (another tenant 42501, so no oracle), SECURITY DEFINER with search_path pinned, not
-- PUBLIC's, executable by the application role.
CREATE FUNCTION tenancy.tenant_legal_name(p_tenant uuid)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
DECLARE
    v_legal_name text;
BEGIN
    IF p_tenant IS DISTINCT FROM shared.rls_tenant_id() THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'tenancy.tenant_legal_name: not the bound tenant';
    END IF;
    SELECT t.legal_name INTO v_legal_name FROM tenancy.tenant t WHERE t.id = p_tenant;
    RETURN v_legal_name;
END;
$$;

REVOKE EXECUTE ON FUNCTION tenancy.tenant_legal_name(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.tenant_legal_name(uuid) TO restaurantpro_app;
