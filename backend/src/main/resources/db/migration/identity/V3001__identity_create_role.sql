-- Roles (D27): a named, editable set of code-defined permissions (access.Permission enum names).
CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.role
(
    id         UUID                     NOT NULL,
    name       VARCHAR(60)              NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT role_pk PRIMARY KEY (id),
    CONSTRAINT role_name_uk UNIQUE (name),
    CONSTRAINT role_name_not_blank_ck CHECK (btrim(name) <> '')
);

CREATE TABLE identity.role_permission
(
    role_id    UUID        NOT NULL,
    permission VARCHAR(40) NOT NULL,
    CONSTRAINT role_permission_pk PRIMARY KEY (role_id, permission),
    CONSTRAINT role_permission_role_fk FOREIGN KEY (role_id) REFERENCES identity.role (id) ON DELETE CASCADE,
    CONSTRAINT role_permission_format_ck CHECK (permission ~ '^[A-Z][A-Z_]*$')
);

-- The seven profiles of spec §3, with fixed ids. The administrator role (…0301) is read-only in the API and
-- must hold every Permission: a migration that adds a Permission constant also adds it here
-- (RoleSeedTest.theAdministratorRoleHoldsEveryPermission fails otherwise).
INSERT INTO identity.role (id, name, version, created_at, updated_at)
VALUES ('01900000-0000-7000-8000-000000000301', 'Administrador', 0, now(), now()),
       ('01900000-0000-7000-8000-000000000302', 'Gerente', 0, now(), now()),
       ('01900000-0000-7000-8000-000000000303', 'Empregado de sala', 0, now(), now()),
       ('01900000-0000-7000-8000-000000000304', 'Caixa', 0, now(), now()),
       ('01900000-0000-7000-8000-000000000305', 'Cozinha/bar', 0, now(), now()),
       ('01900000-0000-7000-8000-000000000306', 'Gestor de stock/compras', 0, now(), now()),
       ('01900000-0000-7000-8000-000000000307', 'Contabilidade/auditor', 0, now(), now());

INSERT INTO identity.role_permission (role_id, permission)
SELECT '01900000-0000-7000-8000-000000000301'::uuid, p
FROM unnest(ARRAY ['COMPANY_MANAGE', 'STORE_MANAGE', 'TERMINAL_MANAGE', 'USER_MANAGE', 'ROLE_MANAGE',
    'EMPLOYEE_MANAGE', 'DINING_MANAGE', 'DINING_VIEW', 'CATALOG_MANAGE', 'CATALOG_PUBLISH', 'CATALOG_VIEW',
    'AUDIT_VIEW', 'SYSTEM_MONITOR']) AS p
UNION ALL
SELECT '01900000-0000-7000-8000-000000000302'::uuid, p
FROM unnest(ARRAY ['TERMINAL_MANAGE', 'EMPLOYEE_MANAGE', 'DINING_MANAGE', 'DINING_VIEW', 'CATALOG_MANAGE',
    'CATALOG_PUBLISH', 'CATALOG_VIEW', 'AUDIT_VIEW']) AS p
UNION ALL
SELECT '01900000-0000-7000-8000-000000000303'::uuid, p
FROM unnest(ARRAY ['DINING_VIEW', 'CATALOG_VIEW']) AS p
UNION ALL
SELECT '01900000-0000-7000-8000-000000000304'::uuid, p
FROM unnest(ARRAY ['DINING_VIEW', 'CATALOG_VIEW']) AS p
UNION ALL
SELECT '01900000-0000-7000-8000-000000000305'::uuid, p
FROM unnest(ARRAY ['CATALOG_VIEW']) AS p
UNION ALL
SELECT '01900000-0000-7000-8000-000000000306'::uuid, p
FROM unnest(ARRAY ['CATALOG_VIEW']) AS p
UNION ALL
SELECT '01900000-0000-7000-8000-000000000307'::uuid, p
FROM unnest(ARRAY ['DINING_VIEW', 'CATALOG_VIEW', 'AUDIT_VIEW']) AS p;
