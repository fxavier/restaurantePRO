-- R6 S71: the tenancy module (D174, D177 as amended, D181/D185 as amended; spec "SQL contracts"). Tenants and their
-- memberships are PLATFORM class (D179): no tenant policy, and the application role holds no privilege on any
-- tenancy table. It reaches them only through the SECURITY DEFINER functions below, each with search_path pinned,
-- EXECUTE revoked from PUBLIC and granted to the application role, and each in RowLevelSecurityCoverageTest's
-- allow-list. Flyway runs this block last, so V1010 (helpers) and V2005 (organization) already exist; nothing
-- references tenancy.tenant by foreign key (cross-module, D6), which is why the bootstrap tenant id is a literal.
CREATE SCHEMA IF NOT EXISTS tenancy;

CREATE TABLE tenancy.tenant
(
    id              uuid                     NOT NULL,
    code            text                     NOT NULL,
    legal_name      text                     NOT NULL,
    status          text                     NOT NULL,
    keycloak_org_id text,
    created_at      timestamp with time zone NOT NULL,
    suspended_at    timestamp with time zone,
    closed_at       timestamp with time zone,
    version         bigint                   NOT NULL DEFAULT 0,
    CONSTRAINT tenant_pk PRIMARY KEY (id),
    CONSTRAINT tenant_code_uk UNIQUE (code),
    CONSTRAINT tenant_keycloak_org_id_uk UNIQUE (keycloak_org_id),
    CONSTRAINT tenant_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT tenant_legal_name_not_blank_ck CHECK (btrim(legal_name) <> ''),
    CONSTRAINT tenant_status_ck CHECK (status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT tenant_closed_at_ck CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE TABLE tenancy.tenant_membership
(
    id           uuid                     NOT NULL,
    tenant_id    uuid                     NOT NULL,
    issuer       text                     NOT NULL,
    subject      text                     NOT NULL,
    kind         text                     NOT NULL,
    principal_id uuid                     NOT NULL,
    status       text                     NOT NULL,
    valid_from   timestamp with time zone NOT NULL,
    valid_until  timestamp with time zone,
    created_at   timestamp with time zone NOT NULL,
    revoked_at   timestamp with time zone,
    version      bigint                   NOT NULL DEFAULT 0,
    CONSTRAINT tenant_membership_pk PRIMARY KEY (id),
    CONSTRAINT tenant_membership_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant (id),
    CONSTRAINT tenant_membership_subject_uk UNIQUE (issuer, subject, tenant_id),
    CONSTRAINT tenant_membership_kind_ck CHECK (kind IN ('ACCOUNT', 'TERMINAL', 'BRIDGE', 'SUPPORT')),
    CONSTRAINT tenant_membership_status_ck CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT tenant_membership_revoked_ck CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CONSTRAINT tenant_membership_issuer_not_blank_ck CHECK (btrim(issuer) <> ''),
    CONSTRAINT tenant_membership_subject_not_blank_ck CHECK (btrim(subject) <> ''),
    CONSTRAINT tenant_membership_validity_ck CHECK (valid_until IS NULL OR valid_until > valid_from),
    -- D183 amended: a support grant is time-boxed, at most 24 h
    CONSTRAINT tenant_membership_support_window_ck CHECK (kind <> 'SUPPORT'
        OR (valid_until IS NOT NULL AND valid_until <= valid_from + interval '24 hours'))
);
CREATE INDEX tenant_membership_tenant_ix ON tenancy.tenant_membership (tenant_id);

-- The bootstrap tenant (D180, P70 = none): the installation's one company, code from its tax id, or 'bootstrap' on an
-- empty database (a fresh install; the company profile is created at first startup).
INSERT INTO tenancy.tenant (id, code, legal_name, status, created_at)
SELECT '00000000-0000-7000-8000-000000000001',
       coalesce((SELECT btrim(tax_id) FROM organization.company_profile
                 WHERE tenant_id = '00000000-0000-7000-8000-000000000001'), 'bootstrap'),
       coalesce((SELECT btrim(name) FROM organization.company_profile
                 WHERE tenant_id = '00000000-0000-7000-8000-000000000001'), 'Bootstrap'),
       'ACTIVE', now();

-- The application role reads memberships only through this function (D177): ACTIVE memberships valid at now() for
-- that (issuer, subject), of the kinds the issuer may hold (D177 amended), and only the requested tenant when one is
-- given. It never lists a tenant the subject does not belong to.
CREATE FUNCTION tenancy.resolve_membership(p_issuer text, p_subject text, p_requested uuid, p_kinds text[])
    RETURNS TABLE
            (
                tenant_id     uuid,
                kind          text,
                principal_id  uuid,
                tenant_status text
            )
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
SELECT m.tenant_id, m.kind, m.principal_id, t.status
FROM tenancy.tenant_membership m
         JOIN tenancy.tenant t ON t.id = m.tenant_id
WHERE m.issuer = p_issuer
  AND m.subject = p_subject
  AND m.status = 'ACTIVE'
  AND m.valid_from <= pg_catalog.now()
  AND (m.valid_until IS NULL OR m.valid_until > pg_catalog.now())
  AND (p_requested IS NULL OR m.tenant_id = p_requested)
  AND m.kind = ANY (p_kinds)
ORDER BY m.tenant_id
$$;

-- D185: the tenants a job iterates (ids only, ACTIVE only).
CREATE FUNCTION tenancy.active_tenant_ids()
    RETURNS SETOF uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
SELECT t.id FROM tenancy.tenant t WHERE t.status = 'ACTIVE' ORDER BY t.id
$$;

-- Memberships are granted and revoked only in the bound tenant: an unbound call raises RS003, another tenant 42501.
CREATE FUNCTION tenancy.grant_membership(p_tenant uuid, p_issuer text, p_subject text, p_kind text,
                                         p_principal uuid, p_valid_from timestamp with time zone,
                                         p_valid_until timestamp with time zone)
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
    VALUES (pg_catalog.uuidv7(), p_tenant, p_issuer, p_subject, p_kind, p_principal, 'ACTIVE', p_valid_from,
            p_valid_until, pg_catalog.now())
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

CREATE FUNCTION tenancy.revoke_membership(p_membership uuid)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
DECLARE
    v_tenant uuid := shared.rls_tenant_id();
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tenancy.tenant_membership m WHERE m.id = p_membership AND m.tenant_id = v_tenant) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'tenancy.revoke_membership: not a membership of the '
            || 'bound tenant';
    END IF;
    UPDATE tenancy.tenant_membership m
    SET status = 'REVOKED', revoked_at = pg_catalog.now(), version = m.version + 1
    WHERE m.id = p_membership
      AND m.tenant_id = v_tenant
      AND m.status = 'ACTIVE';
END;
$$;

-- The bound tenant's own status (TenancyApi.status): never another tenant's (42501), so it is no oracle.
CREATE FUNCTION tenancy.tenant_status(p_tenant uuid)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
DECLARE
    v_status text;
BEGIN
    IF p_tenant IS DISTINCT FROM shared.rls_tenant_id() THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'tenancy.tenant_status: not the bound tenant';
    END IF;
    SELECT t.status INTO v_status FROM tenancy.tenant t WHERE t.id = p_tenant;
    RETURN v_status;
END;
$$;

-- The last step of onboarding (D181 amended): the bound tenant leaves PROVISIONING. Idempotent once ACTIVE; a
-- SUSPENDED or CLOSED tenant is refused (reactivation is a platform command, S75).
CREATE FUNCTION tenancy.activate_tenant(p_tenant uuid)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog AS
$$
DECLARE
    v_status text;
BEGIN
    IF p_tenant IS DISTINCT FROM shared.rls_tenant_id() THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'tenancy.activate_tenant: not the bound tenant';
    END IF;
    SELECT t.status INTO v_status FROM tenancy.tenant t WHERE t.id = p_tenant FOR UPDATE;
    IF v_status = 'ACTIVE' THEN
        RETURN;
    END IF;
    IF v_status IS DISTINCT FROM 'PROVISIONING' THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'tenancy.activate_tenant: the tenant is not provisioning';
    END IF;
    UPDATE tenancy.tenant t SET status = 'ACTIVE', version = t.version + 1 WHERE t.id = p_tenant;
END;
$$;

-- No privilege for the application role on any tenancy table (D177): the owner's default privileges granted DML
-- when the tables were created, so every grantee but the owner loses every table privilege (no role named, as V1008).
-- USAGE on the schema stays: it is needed to call the functions, and grants no access to a table.
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

REVOKE EXECUTE ON FUNCTION tenancy.resolve_membership(text, text, uuid, text[]) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION tenancy.active_tenant_ids() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION tenancy.grant_membership(uuid, text, text, text, uuid, timestamp with time zone,
    timestamp with time zone) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION tenancy.revoke_membership(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION tenancy.tenant_status(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION tenancy.activate_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.resolve_membership(text, text, uuid, text[]) TO restaurantpro_app;
GRANT EXECUTE ON FUNCTION tenancy.active_tenant_ids() TO restaurantpro_app;
GRANT EXECUTE ON FUNCTION tenancy.grant_membership(uuid, text, text, text, uuid, timestamp with time zone,
    timestamp with time zone) TO restaurantpro_app;
GRANT EXECUTE ON FUNCTION tenancy.revoke_membership(uuid) TO restaurantpro_app;
GRANT EXECUTE ON FUNCTION tenancy.tenant_status(uuid) TO restaurantpro_app;
GRANT EXECUTE ON FUNCTION tenancy.activate_tenant(uuid) TO restaurantpro_app;

SELECT shared.rls_register_platform('tenancy.tenant',
                                    'The tenants themselves (D174): no grant to the application role, read only '
                                        || 'through SECURITY DEFINER functions');
SELECT shared.rls_register_platform('tenancy.tenant_membership',
                                    'Memberships (D177): no grant to the application role, resolved only through '
                                        || 'tenancy.resolve_membership');
