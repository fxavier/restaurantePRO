-- R6 S75 (orchestrator re-plan): the subject's tenant list with names, for GET /auth/tenants and the D177 step-8
-- organization-claim check (S73). SECURITY DEFINER like the V15001 functions: the application role holds no privilege
-- on tenancy.*, search_path is pinned, EXECUTE is revoked from PUBLIC and granted to the application role only.
-- Only ACTIVE memberships valid at now() of exactly (issuer, subject) are listed, so it never names a tenant the
-- subject does not belong to.
CREATE FUNCTION tenancy.list_memberships(p_issuer text, p_subject text)
    RETURNS TABLE
            (
                tenant_id       uuid,
                code            text,
                legal_name      text,
                kind            text,
                keycloak_org_id text
            )
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
SELECT m.tenant_id, t.code, t.legal_name, m.kind, t.keycloak_org_id
FROM tenancy.tenant_membership m
         JOIN tenancy.tenant t ON t.id = m.tenant_id
WHERE m.issuer = p_issuer
  AND m.subject = p_subject
  AND m.status = 'ACTIVE'
  AND m.valid_from <= pg_catalog.now()
  AND (m.valid_until IS NULL OR m.valid_until > pg_catalog.now())
ORDER BY t.code, m.tenant_id
$$;

REVOKE EXECUTE ON FUNCTION tenancy.list_memberships(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.list_memberships(text, text) TO restaurantpro_app;
