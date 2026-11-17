-- R6 (S76b): a membership is granted for use at once, so its validity is the database's: valid_from = now() and
-- valid_until = now() + p_valid_for (NULL = no end). V15001's function took both instants from the application's
-- clock; when that clock ran ahead of the database's, resolve_membership (valid_from <= now()) refused the
-- membership just granted (a device enrolling at once got ENROLMENT_CODE_INVALID). Same rules as V15001: the bound
-- tenant only, SECURITY DEFINER with search_path pinned, not PUBLIC's, executable by the application role.
DROP FUNCTION tenancy.grant_membership(uuid, text, text, text, uuid, timestamp with time zone,
    timestamp with time zone);

CREATE FUNCTION tenancy.grant_membership(p_tenant uuid, p_issuer text, p_subject text, p_kind text,
                                         p_principal uuid, p_valid_for interval)
    RETURNS uuid
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
DECLARE
    v_id uuid;
BEGIN
    IF p_tenant IS DISTINCT FROM shared.rls_tenant_id() THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'tenancy.grant_membership: not the bound tenant';
    END IF;
    INSERT INTO tenancy.tenant_membership AS m (id, tenant_id, issuer, subject, kind, principal_id, status, valid_from,
                                                valid_until, created_at)
    VALUES (pg_catalog.uuidv7(), p_tenant, p_issuer, p_subject, p_kind, p_principal, 'ACTIVE', pg_catalog.now(),
            pg_catalog.now() + p_valid_for, pg_catalog.now())
    ON CONFLICT ON CONSTRAINT tenant_membership_subject_uk DO UPDATE
        SET kind         = excluded.kind,
            principal_id = excluded.principal_id,
            status       = 'ACTIVE',
            valid_from   = excluded.valid_from,
            valid_until  = excluded.valid_until,
            revoked_at   = NULL,
            version      = m.version + 1
    RETURNING m.id INTO v_id;
    RETURN v_id;
END;
$$;

REVOKE EXECUTE ON FUNCTION tenancy.grant_membership(uuid, text, text, text, uuid, interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.grant_membership(uuid, text, text, text, uuid, interval) TO restaurantpro_app;
