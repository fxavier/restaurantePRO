-- Row-level security (ADR 0039, D167): identity's tables are UNSCOPED. They are the authority the store scope is
-- derived from, and are read before an actor exists (login, sessions, second factors, federation).
SELECT shared.rls_register_unscoped('identity.role', 'Identity authority: roles, company-wide');
SELECT shared.rls_register_unscoped('identity.role_permission', 'Identity authority: the permissions of a role');
SELECT shared.rls_register_unscoped('identity.employee', 'Identity authority: read at PIN login, before an actor');
SELECT shared.rls_register_unscoped('identity.employee_store_assignment',
                                    'Identity authority: where an employee works, read to build the actor');
SELECT shared.rls_register_unscoped('identity.user_account', 'Identity authority: read at login, before an actor');
SELECT shared.rls_register_unscoped('identity.user_account_role',
                                    'Identity authority: the role grants the store scope is derived from');
SELECT shared.rls_register_unscoped('identity.terminal_session',
                                    'Identity authority: POS sessions, read before the actor is restored');
SELECT shared.rls_register_unscoped('identity.login_throttle', 'Read before authentication: login lockouts');
SELECT shared.rls_register_unscoped('identity.support_grant', 'Identity authority: read at a support login');
SELECT shared.rls_register_unscoped('identity.mfa_factor', 'Identity authority: second factor, read at login');
SELECT shared.rls_register_unscoped('identity.mfa_recovery_code', 'Identity authority: read at login');
SELECT shared.rls_register_unscoped('identity.mfa_enrolment_code', 'Identity authority: read at login');
SELECT shared.rls_register_unscoped('identity.external_identity',
                                    'Identity authority: federated login link, read before an actor');

-- A console function only (D168, S56 review): the superuser console calls it, never the pool.
REVOKE EXECUTE ON FUNCTION identity.mfa_break_glass_reset(text, text) FROM PUBLIC;
