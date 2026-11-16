-- R6 S72: identity on Keycloak (D175, D176, D182, D183, D184) and tenant-isolated (D174, D179, D180 as amended; the
-- spec's per-table recipe). Keycloak owns BackOffice credentials, the second factor, brute-force detection and
-- sessions, so the local password, MFA, login-throttle and OIDC-federation tables and columns go. Every identity table
-- becomes TENANT class (formerly UNSCOPED, D167): the actor is now built inside the resolved tenant's binding, so no
-- identity row is read before a tenant exists. The seeded roles stay the bootstrap tenant's.

-- (0) What Keycloak owns now. The tables leave the UNSCOPED registry first, so it names no dropped table.
DELETE FROM shared.rls_unscoped_table
WHERE table_name IN ('identity.login_throttle'::regclass, 'identity.mfa_recovery_code'::regclass,
                     'identity.mfa_enrolment_code'::regclass, 'identity.mfa_factor'::regclass,
                     'identity.external_identity'::regclass);
DROP FUNCTION identity.mfa_break_glass_reset(text, text);
DROP TABLE identity.login_throttle;
DROP TABLE identity.mfa_recovery_code;
DROP TABLE identity.mfa_enrolment_code;
DROP TABLE identity.mfa_factor;
DROP TABLE identity.external_identity;
ALTER TABLE identity.user_account
    DROP CONSTRAINT user_account_password_hash_format_ck,
    DROP COLUMN password_hash,
    DROP COLUMN must_change_password;
-- The username of an account provisioned at onboarding is its administrator's e-mail address (RFC 5321: 254).
ALTER TABLE identity.user_account
    ALTER COLUMN username TYPE VARCHAR(254);

-- A POS session is an opaque token kept as its SHA-256 (D182), no longer a Spring Session id. The open R2 sessions
-- ended with Spring Session (V1011): their rows are deleted, not rewritten.
DELETE FROM identity.terminal_session;
ALTER TABLE identity.terminal_session
    DROP CONSTRAINT terminal_session_pk,
    DROP COLUMN session_id,
    ADD COLUMN token_hash   VARCHAR(64)              NOT NULL,
    ADD COLUMN last_used_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ADD CONSTRAINT terminal_session_token_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$');

-- A support grant names the support person's ops-realm subject and the SUPPORT membership it created (D183), and
-- records support's first action (the start the tenant's administrators are alerted to). A grant of the R2 local
-- support login has no subject and no membership, so nobody can act on it; it ends by time (at most 24 h).
ALTER TABLE identity.support_grant
    ADD COLUMN subject       VARCHAR(255),
    ADD COLUMN membership_id UUID,
    ADD COLUMN first_used_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE identity.support_grant
    ADD CONSTRAINT support_grant_subject_ck CHECK (subject IS NULL OR btrim(subject) <> '');

-- The administrator role of a tenant (every permission, read-only through the API) is marked, not a fixed id: each
-- tenant has its own. One per tenant.
ALTER TABLE identity.role
    ADD COLUMN administrator BOOLEAN NOT NULL DEFAULT false;
UPDATE identity.role SET administrator = true WHERE id = '01900000-0000-7000-8000-000000000301';

-- The standard roles a provisioned tenant starts with (D181): a snapshot of the roles the identity migrations seeded
-- (V3001, V3005, V3010, V3012), taken before they become the bootstrap tenant's. PLATFORM class (not tenant data, read
-- by every tenant's onboarding, written by migrations only): a later migration that changes a seeded role's
-- permissions changes its template too.
CREATE TABLE identity.role_template
(
    id            UUID        NOT NULL,
    name          VARCHAR(60) NOT NULL,
    administrator BOOLEAN     NOT NULL,
    CONSTRAINT role_template_pk PRIMARY KEY (id),
    CONSTRAINT role_template_name_uk UNIQUE (name)
);
CREATE TABLE identity.role_template_permission
(
    template_id UUID        NOT NULL,
    permission  VARCHAR(40) NOT NULL,
    CONSTRAINT role_template_permission_pk PRIMARY KEY (template_id, permission),
    CONSTRAINT role_template_permission_template_fk FOREIGN KEY (template_id) REFERENCES identity.role_template (id)
);
INSERT INTO identity.role_template (id, name, administrator)
SELECT id, name, administrator FROM identity.role
WHERE id BETWEEN '01900000-0000-7000-8000-000000000301' AND '01900000-0000-7000-8000-000000000307';
INSERT INTO identity.role_template_permission (template_id, permission)
SELECT role_id, permission FROM identity.role_permission
WHERE role_id IN (SELECT id FROM identity.role_template);
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON identity.role_template, identity.role_template_permission
    FROM restaurantpro_app;
SELECT shared.rls_register_platform('identity.role_template',
                                    'The standard roles of a new tenant (D181): seeded by migrations, no tenant data');
SELECT shared.rls_register_platform('identity.role_template_permission',
                                    'The permissions of the standard roles of a new tenant (D181)');

-- (1)+(2) tenant_id on every table, parents first.
ALTER TABLE identity.role
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE identity.role
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE identity.role_permission
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE identity.role_permission
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE identity.employee
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE identity.employee
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE identity.employee_store_assignment
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE identity.employee_store_assignment
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE identity.user_account
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE identity.user_account
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE identity.user_account_role
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE identity.user_account_role
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE identity.terminal_session
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE identity.terminal_session
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE identity.support_grant
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE identity.support_grant
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) The table's identity includes the tenant: UNIQUE (tenant_id, id) on every table with an id, parents first; a
-- primary key leading with tenant_id on the tables without one.
ALTER TABLE identity.role
    ADD CONSTRAINT role_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE identity.employee
    ADD CONSTRAINT employee_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE identity.user_account
    ADD CONSTRAINT user_account_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE identity.employee_store_assignment
    ADD CONSTRAINT employee_store_assignment_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE identity.support_grant
    ADD CONSTRAINT support_grant_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE identity.role_permission
    DROP CONSTRAINT role_permission_pk,
    ADD CONSTRAINT role_permission_pk PRIMARY KEY (tenant_id, role_id, permission);
ALTER TABLE identity.user_account_role
    DROP CONSTRAINT user_account_role_pk,
    ADD CONSTRAINT user_account_role_pk PRIMARY KEY (tenant_id, user_account_id, role_id);
ALTER TABLE identity.terminal_session
    ADD CONSTRAINT terminal_session_pk PRIMARY KEY (tenant_id, token_hash);

-- (4) Business unique keys per tenant: create the (tenant_id, ...) one, drop the old, keep the old name (the
-- application maps a violation by constraint name, UniqueConstraints).
ALTER TABLE identity.role
    ADD CONSTRAINT role_tenant_name_uk UNIQUE (tenant_id, name);
ALTER TABLE identity.role
    DROP CONSTRAINT role_name_uk;
ALTER TABLE identity.role
    RENAME CONSTRAINT role_tenant_name_uk TO role_name_uk;
CREATE UNIQUE INDEX role_one_administrator_uk ON identity.role (tenant_id) WHERE administrator;

ALTER TABLE identity.employee
    ADD CONSTRAINT employee_tenant_code_uk UNIQUE (tenant_id, code);
ALTER TABLE identity.employee
    DROP CONSTRAINT employee_code_uk;
ALTER TABLE identity.employee
    RENAME CONSTRAINT employee_tenant_code_uk TO employee_code_uk;

ALTER TABLE identity.employee_store_assignment
    ADD CONSTRAINT employee_store_assignment_tenant_uk UNIQUE (tenant_id, employee_id, store_id, role_id);
ALTER TABLE identity.employee_store_assignment
    DROP CONSTRAINT employee_store_assignment_uk;
ALTER TABLE identity.employee_store_assignment
    RENAME CONSTRAINT employee_store_assignment_tenant_uk TO employee_store_assignment_uk;
DROP INDEX identity.employee_store_assignment_store_ix;
CREATE INDEX employee_store_assignment_store_ix ON identity.employee_store_assignment (tenant_id, store_id);

ALTER TABLE identity.user_account
    ADD CONSTRAINT user_account_tenant_username_uk UNIQUE (tenant_id, username),
    ADD CONSTRAINT user_account_tenant_employee_uk UNIQUE (tenant_id, employee_id);
ALTER TABLE identity.user_account
    DROP CONSTRAINT user_account_username_uk,
    DROP CONSTRAINT user_account_employee_uk;
ALTER TABLE identity.user_account
    RENAME CONSTRAINT user_account_tenant_username_uk TO user_account_username_uk;
ALTER TABLE identity.user_account
    RENAME CONSTRAINT user_account_tenant_employee_uk TO user_account_employee_uk;

DROP INDEX identity.support_grant_one_active_uk;
CREATE UNIQUE INDEX support_grant_one_active_uk ON identity.support_grant (tenant_id, account_id)
    WHERE revoked_at IS NULL AND expired_at IS NULL;
DROP INDEX identity.support_grant_active_until_ix;

DROP INDEX identity.terminal_session_terminal_ix;
DROP INDEX identity.terminal_session_store_ix;
CREATE INDEX terminal_session_terminal_ix ON identity.terminal_session (tenant_id, terminal_id);
CREATE INDEX terminal_session_store_ix ON identity.terminal_session (tenant_id, store_id);
CREATE INDEX terminal_session_employee_ix ON identity.terminal_session (tenant_id, employee_id);

-- (5) In-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, then drop the
-- single-column one and keep its name.
ALTER TABLE identity.role_permission
    ADD CONSTRAINT role_permission_tenant_role_fk FOREIGN KEY (tenant_id, role_id)
        REFERENCES identity.role (tenant_id, id) ON DELETE CASCADE NOT VALID;
ALTER TABLE identity.employee_store_assignment
    ADD CONSTRAINT employee_store_assignment_tenant_employee_fk FOREIGN KEY (tenant_id, employee_id)
        REFERENCES identity.employee (tenant_id, id) NOT VALID,
    ADD CONSTRAINT employee_store_assignment_tenant_role_fk FOREIGN KEY (tenant_id, role_id)
        REFERENCES identity.role (tenant_id, id) NOT VALID;
ALTER TABLE identity.user_account
    ADD CONSTRAINT user_account_tenant_employee_fk FOREIGN KEY (tenant_id, employee_id)
        REFERENCES identity.employee (tenant_id, id) NOT VALID;
ALTER TABLE identity.user_account_role
    ADD CONSTRAINT user_account_role_tenant_user_fk FOREIGN KEY (tenant_id, user_account_id)
        REFERENCES identity.user_account (tenant_id, id) ON DELETE CASCADE NOT VALID,
    ADD CONSTRAINT user_account_role_tenant_role_fk FOREIGN KEY (tenant_id, role_id)
        REFERENCES identity.role (tenant_id, id) NOT VALID;
ALTER TABLE identity.support_grant
    ADD CONSTRAINT support_grant_tenant_account_fk FOREIGN KEY (tenant_id, account_id)
        REFERENCES identity.user_account (tenant_id, id) NOT VALID,
    ADD CONSTRAINT support_grant_tenant_granted_by_fk FOREIGN KEY (tenant_id, granted_by_account_id)
        REFERENCES identity.user_account (tenant_id, id) NOT VALID,
    ADD CONSTRAINT support_grant_tenant_revoked_by_fk FOREIGN KEY (tenant_id, revoked_by_account_id)
        REFERENCES identity.user_account (tenant_id, id) NOT VALID;

ALTER TABLE identity.role_permission VALIDATE CONSTRAINT role_permission_tenant_role_fk;
ALTER TABLE identity.employee_store_assignment VALIDATE CONSTRAINT employee_store_assignment_tenant_employee_fk;
ALTER TABLE identity.employee_store_assignment VALIDATE CONSTRAINT employee_store_assignment_tenant_role_fk;
ALTER TABLE identity.user_account VALIDATE CONSTRAINT user_account_tenant_employee_fk;
ALTER TABLE identity.user_account_role VALIDATE CONSTRAINT user_account_role_tenant_user_fk;
ALTER TABLE identity.user_account_role VALIDATE CONSTRAINT user_account_role_tenant_role_fk;
ALTER TABLE identity.support_grant VALIDATE CONSTRAINT support_grant_tenant_account_fk;
ALTER TABLE identity.support_grant VALIDATE CONSTRAINT support_grant_tenant_granted_by_fk;
ALTER TABLE identity.support_grant VALIDATE CONSTRAINT support_grant_tenant_revoked_by_fk;

ALTER TABLE identity.role_permission DROP CONSTRAINT role_permission_role_fk;
ALTER TABLE identity.employee_store_assignment
    DROP CONSTRAINT employee_store_assignment_employee_fk,
    DROP CONSTRAINT employee_store_assignment_role_fk;
ALTER TABLE identity.user_account DROP CONSTRAINT user_account_employee_fk;
ALTER TABLE identity.user_account_role
    DROP CONSTRAINT user_account_role_user_fk,
    DROP CONSTRAINT user_account_role_role_fk;
ALTER TABLE identity.support_grant
    DROP CONSTRAINT support_grant_account_fk,
    DROP CONSTRAINT support_grant_granted_by_fk,
    DROP CONSTRAINT support_grant_revoked_by_fk;

ALTER TABLE identity.role_permission RENAME CONSTRAINT role_permission_tenant_role_fk TO role_permission_role_fk;
ALTER TABLE identity.employee_store_assignment
    RENAME CONSTRAINT employee_store_assignment_tenant_employee_fk TO employee_store_assignment_employee_fk;
ALTER TABLE identity.employee_store_assignment
    RENAME CONSTRAINT employee_store_assignment_tenant_role_fk TO employee_store_assignment_role_fk;
ALTER TABLE identity.user_account RENAME CONSTRAINT user_account_tenant_employee_fk TO user_account_employee_fk;
ALTER TABLE identity.user_account_role RENAME CONSTRAINT user_account_role_tenant_user_fk TO user_account_role_user_fk;
ALTER TABLE identity.user_account_role RENAME CONSTRAINT user_account_role_tenant_role_fk TO user_account_role_role_fk;
ALTER TABLE identity.support_grant RENAME CONSTRAINT support_grant_tenant_account_fk TO support_grant_account_fk;
ALTER TABLE identity.support_grant
    RENAME CONSTRAINT support_grant_tenant_granted_by_fk TO support_grant_granted_by_fk;
ALTER TABLE identity.support_grant
    RENAME CONSTRAINT support_grant_tenant_revoked_by_fk TO support_grant_revoked_by_fk;

-- (6) The policies through the helper (TENANT class), then the registries (out of UNSCOPED).
DELETE FROM shared.rls_unscoped_table
WHERE table_name IN ('identity.role'::regclass, 'identity.role_permission'::regclass,
                     'identity.employee'::regclass, 'identity.employee_store_assignment'::regclass,
                     'identity.user_account'::regclass, 'identity.user_account_role'::regclass,
                     'identity.terminal_session'::regclass, 'identity.support_grant'::regclass);
SELECT shared.rls_protect_tenant_table('identity.role');
SELECT shared.rls_protect_tenant_table('identity.role_permission');
SELECT shared.rls_protect_tenant_table('identity.employee');
SELECT shared.rls_protect_tenant_table('identity.employee_store_assignment');
SELECT shared.rls_protect_tenant_table('identity.user_account');
SELECT shared.rls_protect_tenant_table('identity.user_account_role');
SELECT shared.rls_protect_tenant_table('identity.terminal_session');
SELECT shared.rls_protect_tenant_table('identity.support_grant');
