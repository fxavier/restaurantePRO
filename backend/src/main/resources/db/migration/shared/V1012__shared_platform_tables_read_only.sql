-- R6 (D179): PLATFORM tables are shared by every tenant and have no tenant policy, so the pool may not write them (one
-- tenant would change them for all). The owner's default privileges grant DML on every table a migration creates;
-- registering a table PLATFORM now takes the writes back from every grantee but the owner (no role named, as V1008),
-- unless the application must write it. deploy/postgres/provision-roles.sql calls the same function after its grants
-- at every start, so this is the one place the allow-list lives.

-- The PLATFORM tables the application writes: Spring Modulith's event registry and the per-tenant job leases (D185).
CREATE FUNCTION shared.rls_platform_writable(p_table regclass) RETURNS boolean
    LANGUAGE sql
    STABLE AS
$$
SELECT p_table IN (to_regclass('public.event_publication'), to_regclass('public.event_publication_archive'),
                   to_regclass('shared.job_lease'))
$$;

CREATE FUNCTION shared.rls_revoke_platform_writes(p_table regclass) RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    v_grantee regrole;
BEGIN
    IF shared.rls_platform_writable(p_table) THEN
        RETURN;
    END IF;
    FOR v_grantee IN
        SELECT DISTINCT a.grantee::regrole
        FROM pg_catalog.pg_class c,
             LATERAL pg_catalog.aclexplode(c.relacl) a
        WHERE c.oid = p_table
          AND a.grantee <> c.relowner
          AND a.grantee <> 0
          AND a.privilege_type IN ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')
        LOOP
            EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON %s FROM %s', p_table, v_grantee);
        END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION shared.rls_register_platform(p_table regclass, p_reason text) RETURNS void
    LANGUAGE plpgsql AS
$$
BEGIN
    DELETE FROM shared.rls_unscoped_table WHERE table_name = p_table;
    INSERT INTO shared.rls_platform_table (table_name, reason)
    VALUES (p_table, p_reason)
    ON CONFLICT (table_name) DO UPDATE SET reason = excluded.reason;
    PERFORM shared.rls_revoke_platform_writes(p_table);
END;
$$;

-- Every table registered before this migration.
SELECT shared.rls_revoke_platform_writes(table_name)
FROM shared.rls_platform_table
ORDER BY table_name::text;
