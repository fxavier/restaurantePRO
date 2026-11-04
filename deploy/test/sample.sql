-- Sample data for test-backup-restore.sh (PRD S5, acceptance criterion 10).
-- Stand-in for the real schema: several module schemas, money as numeric, uuid, jsonb,
-- timestamptz, NULLs, non-ASCII text, a sequence, a foreign key and an append-only trigger.
-- S6 re-runs the drill against the real Flyway schema.
CREATE SCHEMA organization;
CREATE SCHEMA audit;

CREATE TABLE organization.company (
    id       boolean PRIMARY KEY DEFAULT true CHECK (id),
    name     text NOT NULL,
    tax_id   text NOT NULL,
    country  char(2) NOT NULL,
    currency char(3) NOT NULL
);

CREATE TABLE organization.store (
    id         uuid PRIMARY KEY,
    code       text NOT NULL UNIQUE,
    name       text NOT NULL,
    timezone   text NOT NULL,
    active     boolean NOT NULL,
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL
);

CREATE TABLE organization.price_sample (
    id       bigserial PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES organization.store (id),
    amount   numeric(19, 4) NOT NULL,
    note     text
);

CREATE TABLE audit.audit_event (
    id          bigserial PRIMARY KEY,
    action      text NOT NULL,
    details     jsonb NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE FUNCTION audit.reject_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END
$$;

CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit.audit_event
    FOR EACH ROW EXECUTE FUNCTION audit.reject_change();

INSERT INTO organization.company (name, tax_id, country, currency)
VALUES ('Restaurante Exemplo, Lda', '400000000', 'MZ', 'MZN');

INSERT INTO organization.store (id, code, name, timezone, active, created_at)
SELECT md5('store-' || g)::uuid,
       'S' || lpad(g::text, 3, '0'),
       'Loja ' || g || ' — Maputo',
       'Africa/Maputo',
       g % 4 <> 0,
       timestamptz '2026-01-01 08:00:00+02' + g * interval '1 day'
FROM generate_series(1, 12) AS g;

INSERT INTO organization.price_sample (store_id, amount, note)
SELECT md5('store-' || (1 + g % 12))::uuid,
       round((g * 13.37)::numeric, 4),
       CASE WHEN g % 7 = 0 THEN NULL ELSE 'preço nº ' || g END
FROM generate_series(1, 5000) AS g;

INSERT INTO audit.audit_event (action, details, occurred_at)
SELECT 'STORE_UPDATED',
       jsonb_build_object('storeCode', 'S' || lpad((1 + g % 12)::text, 3, '0'), 'seq', g),
       timestamptz '2026-02-01 00:00:00Z' + g * interval '1 minute'
FROM generate_series(1, 300) AS g;

-- Spring Session JDBC tables (same names as the backend's shared schema). backup.sh dumps their
-- structure but not their rows: restored sessions are useless and only expose session data.
CREATE SCHEMA shared;

CREATE TABLE shared.spring_session (
    primary_id            char(36) PRIMARY KEY,
    session_id            char(36) NOT NULL UNIQUE,
    creation_time         bigint NOT NULL,
    last_access_time      bigint NOT NULL,
    max_inactive_interval int NOT NULL,
    expiry_time           bigint NOT NULL,
    principal_name        varchar(100)
);
CREATE INDEX spring_session_ix2 ON shared.spring_session (expiry_time);

CREATE TABLE shared.spring_session_attributes (
    session_primary_id char(36) NOT NULL REFERENCES shared.spring_session (primary_id) ON DELETE CASCADE,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);

INSERT INTO shared.spring_session
SELECT md5('p' || g)::uuid::text, md5('s' || g)::uuid::text, 0, 0, 1800, 1800, 'user' || g
FROM generate_series(1, 3) AS g;

INSERT INTO shared.spring_session_attributes
SELECT md5('p' || g)::uuid::text, 'SPRING_SECURITY_CONTEXT', '\xdeadbeef'::bytea
FROM generate_series(1, 3) AS g;
