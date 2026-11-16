-- R6 S75. (1) The orchestrator re-plan: the subject's tenant list with names, for GET /auth/tenants and the D177 step-8
-- organization-claim check (S73). SECURITY DEFINER like the V15001 functions: the application role holds no privilege
-- on tenancy.*, search_path is pinned, EXECUTE is revoked from PUBLIC and granted to the application role only.
-- Only ACTIVE memberships valid at now() of exactly (issuer, subject) are listed, so it never names a tenant the
-- subject does not belong to. The tenant's status is returned, not filtered: the caller shows or refuses a SUSPENDED or
-- CLOSED tenant.
CREATE FUNCTION tenancy.list_memberships(p_issuer text, p_subject text)
    RETURNS TABLE
            (
                tenant_id       uuid,
                code            text,
                legal_name      text,
                kind            text,
                keycloak_org_id text,
                tenant_status   text
            )
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
SELECT m.tenant_id, t.code, t.legal_name, m.kind, t.keycloak_org_id, t.status
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

-- Platform writes (D181 as amended: no owner pool; spec "SQL contracts"). /api/v1/platform/** runs with no tenant
-- binding and changes tenancy.* only through the three functions below. Every platform action is one append-only row
-- of tenancy.platform_audit_event (D18): operator (the ops-realm subject), tenant, action, reason.
CREATE TABLE tenancy.platform_audit_event
(
    id          uuid                     NOT NULL DEFAULT pg_catalog.uuidv7(),
    occurred_at timestamp with time zone NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    operator    text                     NOT NULL,
    tenant_id   uuid,
    action      text                     NOT NULL,
    reason      text,
    CONSTRAINT platform_audit_event_pk PRIMARY KEY (id),
    CONSTRAINT platform_audit_event_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant (id),
    CONSTRAINT platform_audit_event_operator_not_blank_ck CHECK (btrim(operator) <> ''),
    CONSTRAINT platform_audit_event_action_ck CHECK (action ~ '^[A-Z][A-Z_]*$')
);
CREATE INDEX platform_audit_event_tenant_ix ON tenancy.platform_audit_event (tenant_id, occurred_at);

CREATE FUNCTION tenancy.reject_platform_audit_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'tenancy.platform_audit_event is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER platform_audit_event_no_update_or_delete
    BEFORE UPDATE OR DELETE
    ON tenancy.platform_audit_event
    FOR EACH ROW
EXECUTE FUNCTION tenancy.reject_platform_audit_change();

CREATE TRIGGER platform_audit_event_no_truncate
    BEFORE TRUNCATE
    ON tenancy.platform_audit_event
    FOR EACH STATEMENT
EXECUTE FUNCTION tenancy.reject_platform_audit_change();

-- Defence in depth (G5): platform writes run with NO tenant binding (/platform/** requests and platform jobs). A
-- tenant-bound transaction — a tenant user's request that reached a platform code path — is refused with TN004.
CREATE FUNCTION tenancy.require_platform_transaction(p_caller text)
    RETURNS void
    LANGUAGE plpgsql
    STABLE
    SET search_path = pg_catalog AS
$$
BEGIN
    IF coalesce(pg_catalog.current_setting('app.tenant_id', true), '') <> '' THEN
        RAISE EXCEPTION USING ERRCODE = 'TN004', MESSAGE = p_caller || ': a tenant-bound transaction cannot make a '
            || 'platform write';
    END IF;
END;
$$;

-- Appends one platform audit row (an action of the platform command that changes no tenant row).
CREATE FUNCTION tenancy.record_platform_event(p_tenant uuid, p_operator text, p_action text, p_reason text)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
BEGIN
    PERFORM tenancy.require_platform_transaction('tenancy.record_platform_event');
    INSERT INTO tenancy.platform_audit_event (operator, tenant_id, action, reason)
    VALUES (p_operator, p_tenant, p_action, p_reason);
END;
$$;

-- Onboarding's first step, idempotent by the tenant code (the Keycloak organization alias): a new code creates a
-- PROVISIONING tenant (audited TENANT_CREATED); the same code with the same legal name returns that tenant; another
-- legal name is refused (TN001). With p_keycloak_org_id, records the organization the platform command created
-- (audited ORGANIZATION_LINKED) unless it is already recorded; another organization is refused (TN001).
CREATE FUNCTION tenancy.create_tenant(p_code text, p_legal_name text, p_keycloak_org_id text, p_operator text)
    RETURNS TABLE
            (
                tenant_id       uuid,
                status          text,
                keycloak_org_id text
            )
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
#variable_conflict use_column
DECLARE
    v tenancy.tenant%ROWTYPE;
BEGIN
    PERFORM tenancy.require_platform_transaction('tenancy.create_tenant');
    INSERT INTO tenancy.tenant AS t (id, code, legal_name, status, created_at)
    VALUES (pg_catalog.uuidv7(), p_code, p_legal_name, 'PROVISIONING', pg_catalog.now())
    ON CONFLICT ON CONSTRAINT tenant_code_uk DO NOTHING
    RETURNING t.* INTO v;
    IF FOUND THEN
        INSERT INTO tenancy.platform_audit_event (operator, tenant_id, action) VALUES (p_operator, v.id, 'TENANT_CREATED');
    ELSE
        SELECT t.* INTO v FROM tenancy.tenant t WHERE t.code = p_code FOR UPDATE;
        IF v.legal_name IS DISTINCT FROM p_legal_name THEN
            RAISE EXCEPTION USING ERRCODE = 'TN001', MESSAGE = 'tenancy.create_tenant: the code belongs to another '
                || 'tenant';
        END IF;
    END IF;
    IF p_keycloak_org_id IS NOT NULL THEN
        IF v.keycloak_org_id IS NULL THEN
            UPDATE tenancy.tenant t SET keycloak_org_id = p_keycloak_org_id, version = t.version + 1 WHERE t.id = v.id;
            INSERT INTO tenancy.platform_audit_event (operator, tenant_id, action)
            VALUES (p_operator, v.id, 'ORGANIZATION_LINKED');
            v.keycloak_org_id := p_keycloak_org_id;
        ELSIF v.keycloak_org_id <> p_keycloak_org_id THEN
            RAISE EXCEPTION USING ERRCODE = 'TN001', MESSAGE = 'tenancy.create_tenant: another organization is '
                || 'already linked';
        END IF;
    END IF;
    RETURN QUERY SELECT v.id, v.status, v.keycloak_org_id;
END;
$$;

-- Suspension, reactivation and closure (D181): ACTIVE → SUSPENDED → ACTIVE, SUSPENDED or PROVISIONING → CLOSED, with
-- a reason, audited. The status the tenant is already in is a no-op (changed = false, nothing audited); any other
-- transition is refused (TN002); an unknown tenant is TN003. PROVISIONING → ACTIVE is activate_tenant's (identity).
CREATE FUNCTION tenancy.set_tenant_status(p_tenant uuid, p_status text, p_operator text, p_reason text)
    RETURNS TABLE
            (
                code    text,
                status  text,
                changed boolean
            )
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
#variable_conflict use_column
DECLARE
    v_from text;
    v_code text;
BEGIN
    PERFORM tenancy.require_platform_transaction('tenancy.set_tenant_status');
    IF p_reason IS NULL OR pg_catalog.btrim(p_reason) = '' THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'tenancy.set_tenant_status: a reason is required';
    END IF;
    SELECT t.status, t.code INTO v_from, v_code FROM tenancy.tenant t WHERE t.id = p_tenant FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = 'TN003', MESSAGE = 'tenancy.set_tenant_status: no such tenant';
    END IF;
    IF v_from = p_status THEN
        RETURN QUERY SELECT v_code, v_from, false;
        RETURN;
    END IF;
    IF NOT ((v_from = 'ACTIVE' AND p_status = 'SUSPENDED')
        OR (v_from = 'SUSPENDED' AND p_status = 'ACTIVE')
        OR (v_from IN ('SUSPENDED', 'PROVISIONING') AND p_status = 'CLOSED')) THEN
        RAISE EXCEPTION USING ERRCODE = 'TN002', MESSAGE = 'tenancy.set_tenant_status: ' || v_from || ' cannot become '
            || p_status;
    END IF;
    UPDATE tenancy.tenant t
    SET status       = p_status,
        suspended_at = CASE WHEN p_status = 'SUSPENDED' THEN pg_catalog.now() ELSE t.suspended_at END,
        closed_at    = CASE WHEN p_status = 'CLOSED' THEN pg_catalog.now() END,
        version      = t.version + 1
    WHERE t.id = p_tenant;
    INSERT INTO tenancy.platform_audit_event (operator, tenant_id, action, reason)
    VALUES (p_operator, p_tenant, CASE p_status WHEN 'SUSPENDED' THEN 'TENANT_SUSPENDED'
                                                WHEN 'ACTIVE' THEN 'TENANT_REACTIVATED'
                                                ELSE 'TENANT_CLOSED' END, p_reason);
    RETURN QUERY SELECT v_code, p_status, true;
END;
$$;

-- As V15001: no table privilege for any role but the owner (the default privileges granted DML on the new table).
DO
$$
DECLARE
    v_grant record;
BEGIN
    FOR v_grant IN
        SELECT DISTINCT c.oid::regclass AS tbl, a.grantee::regrole AS grantee
        FROM pg_catalog.pg_class c
                 JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace,
             LATERAL pg_catalog.aclexplode(c.relacl) a
        WHERE n.nspname = 'tenancy'
          AND a.grantee <> c.relowner
          AND a.grantee <> 0
        LOOP
            EXECUTE format('REVOKE ALL ON TABLE %s FROM %s', v_grant.tbl, v_grant.grantee);
        END LOOP;
END;
$$;

REVOKE EXECUTE ON FUNCTION tenancy.reject_platform_audit_change() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION tenancy.require_platform_transaction(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION tenancy.record_platform_event(uuid, text, text, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION tenancy.create_tenant(text, text, text, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION tenancy.set_tenant_status(uuid, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.record_platform_event(uuid, text, text, text) TO restaurantpro_app;
GRANT EXECUTE ON FUNCTION tenancy.create_tenant(text, text, text, text) TO restaurantpro_app;
GRANT EXECUTE ON FUNCTION tenancy.set_tenant_status(uuid, text, text, text) TO restaurantpro_app;

SELECT shared.rls_register_platform('tenancy.platform_audit_event',
                                    'The platform audit trail (D181): append-only, no grant to the application role, '
                                        || 'written only by the platform SECURITY DEFINER functions');
