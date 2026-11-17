-- R6 (S76b): deploy/postgres/provision-roles.sql calls these V1012 functions as a superuser at every start, so they
-- resolve nothing through the caller's search_path (an object planted earlier on it would run with those rights).
-- Every name inside them is already schema-qualified; pg_catalog alone also makes format('%s', regclass) name the
-- schema in the REVOKE. The allow-list in shared.rls_platform_writable has a copy in the test
-- PlatformTablePrivilegesTest.WRITTEN_BY_THE_APPLICATION: change both together.
ALTER FUNCTION shared.rls_platform_writable(regclass) SET search_path = pg_catalog;
ALTER FUNCTION shared.rls_revoke_platform_writes(regclass) SET search_path = pg_catalog;
