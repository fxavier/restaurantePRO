-- Database roles of an installation (ADR 0039, D163). Run by the superuser with psql, at every start, by restore,
-- by the tests and in development:
--
--   psql -v ON_ERROR_STOP=1 -v owner_password=... -v app_password=... -f provision-roles.sql
--
-- (deploy/postgres/provision.sh reads the two passwords from secret files with \getenv; never on a command line in
-- production.) Idempotent: every step is a no-op when already done, and the role attributes and passwords are
-- reasserted every run, so a PITR restore that brought back older password hashes is re-synced. Never echoes a
-- password: no \echo of the variables, statement logging is off for this session, and psql does not echo statements.
--
--   restaurantpro        the bootstrap superuser: operations only (backup, restore, PITR, console). Never the backend.
--   restaurantpro_owner  LOGIN NOSUPERUSER BYPASSRLS; owns every application object; Flyway's login.
--   restaurantpro_app    LOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT; owns nothing; the pool. Row-level security binds it.

\set ON_ERROR_STOP on
\set QUIET on

SET log_statement = 'none';
SET log_min_error_statement = 'panic';

-- 1. The two roles, created when missing; attributes reasserted every run.
SELECT 'CREATE ROLE restaurantpro_owner'
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'restaurantpro_owner')
\gexec
SELECT 'CREATE ROLE restaurantpro_app'
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'restaurantpro_app')
\gexec
ALTER ROLE restaurantpro_owner WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION BYPASSRLS INHERIT;
ALTER ROLE restaurantpro_app WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS NOINHERIT;

-- 2. Their passwords (psql quotes the variables as literals).
ALTER ROLE restaurantpro_owner PASSWORD :'owner_password';
ALTER ROLE restaurantpro_app PASSWORD :'app_password';

-- 3. The owner creates schemas and the Flyway history table.
SELECT format('GRANT CREATE, CONNECT, TEMPORARY ON DATABASE %I TO restaurantpro_owner', current_database())
\gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO restaurantpro_app', current_database())
\gexec
GRANT USAGE, CREATE ON SCHEMA public TO restaurantpro_owner;

-- 4. Ownership of every application object moves to the owner (an R5 installation's objects belong to the
-- superuser). REASSIGN OWNED cannot be used on the bootstrap superuser. Objects of an extension are skipped (the
-- extension keeps its owner), so are system schemas and objects the owner already has. Tables carry their indexes,
-- triggers, policies and owned sequences with them.
CREATE TEMPORARY VIEW provision_schema AS
SELECT n.oid, n.nspname
FROM pg_catalog.pg_namespace n
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
  AND n.nspname NOT LIKE 'pg\_temp\_%'
  AND n.nspname NOT LIKE 'pg\_toast\_temp\_%';

CREATE TEMPORARY VIEW provision_extension_member AS
SELECT d.classid, d.objid
FROM pg_catalog.pg_depend d
WHERE d.deptype = 'e';

SELECT format('ALTER SCHEMA %I OWNER TO restaurantpro_owner', s.nspname)
FROM provision_schema s
         JOIN pg_catalog.pg_namespace n ON n.oid = s.oid
WHERE s.nspname <> 'public'
  AND n.nspowner <> 'restaurantpro_owner'::regrole
  AND NOT EXISTS (SELECT 1 FROM provision_extension_member e
                  WHERE e.classid = 'pg_catalog.pg_namespace'::regclass AND e.objid = s.oid)
ORDER BY s.nspname
\gexec

SELECT format('ALTER %s %s OWNER TO restaurantpro_owner',
              CASE c.relkind
                  WHEN 'v' THEN 'VIEW'
                  WHEN 'm' THEN 'MATERIALIZED VIEW'
                  WHEN 'S' THEN 'SEQUENCE'
                  WHEN 'f' THEN 'FOREIGN TABLE'
                  ELSE 'TABLE' END,
              c.oid::regclass)
FROM pg_catalog.pg_class c
         JOIN provision_schema s ON s.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
  AND c.relowner <> 'restaurantpro_owner'::regrole
  AND NOT EXISTS (SELECT 1 FROM provision_extension_member e
                  WHERE e.classid = 'pg_catalog.pg_class'::regclass AND e.objid = c.oid)
  -- a sequence owned by a column moves with its table
  AND NOT (c.relkind = 'S' AND EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                                       WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid
                                         AND d.deptype IN ('a', 'i')))
ORDER BY c.oid
\gexec

SELECT format('ALTER ROUTINE %s OWNER TO restaurantpro_owner', p.oid::regprocedure)
FROM pg_catalog.pg_proc p
         JOIN provision_schema s ON s.oid = p.pronamespace
WHERE p.proowner <> 'restaurantpro_owner'::regrole
  AND NOT EXISTS (SELECT 1 FROM provision_extension_member e
                  WHERE e.classid = 'pg_catalog.pg_proc'::regclass AND e.objid = p.oid)
ORDER BY p.oid
\gexec

SELECT format('ALTER TYPE %s OWNER TO restaurantpro_owner', t.oid::regtype)
FROM pg_catalog.pg_type t
         JOIN provision_schema s ON s.oid = t.typnamespace
WHERE t.typowner <> 'restaurantpro_owner'::regrole
  AND t.typtype IN ('e', 'd', 'r', 'm')
  AND NOT EXISTS (SELECT 1 FROM provision_extension_member e
                  WHERE e.classid = 'pg_catalog.pg_type'::regclass AND e.objid = t.oid)
ORDER BY t.oid
\gexec

-- 5. The application's privileges on everything that exists: data, never DDL, TRUNCATE, REFERENCES or TRIGGER.
SELECT format('GRANT USAGE ON SCHEMA %I TO restaurantpro_app', s.nspname)
FROM provision_schema s
ORDER BY s.nspname
\gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO restaurantpro_app', s.nspname)
FROM provision_schema s
ORDER BY s.nspname
\gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO restaurantpro_app', s.nspname)
FROM provision_schema s
ORDER BY s.nspname
\gexec

-- 6. The same for what later migrations create (the owner runs them).
ALTER DEFAULT PRIVILEGES FOR ROLE restaurantpro_owner GRANT USAGE ON SCHEMAS TO restaurantpro_app;
ALTER DEFAULT PRIVILEGES FOR ROLE restaurantpro_owner
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO restaurantpro_app;
ALTER DEFAULT PRIVILEGES FOR ROLE restaurantpro_owner GRANT USAGE, SELECT ON SEQUENCES TO restaurantpro_app;

DROP VIEW provision_extension_member;
DROP VIEW provision_schema;
