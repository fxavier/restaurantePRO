-- R6 S72 (D176): the backend is a stateless OAuth2 resource server; no server-side session remains. Spring Session
-- JDBC's tables (V1002) and the console function that ended an account's sessions in them (V1007) go away with it.
-- Their UNSCOPED registrations (V1008) go first, so the registry names no dropped table.
DELETE FROM shared.rls_unscoped_table
WHERE table_name IN ('shared.spring_session'::regclass, 'shared.spring_session_attributes'::regclass);

DROP FUNCTION shared.end_account_sessions(uuid);
DROP TABLE shared.spring_session_attributes;
DROP TABLE shared.spring_session;
