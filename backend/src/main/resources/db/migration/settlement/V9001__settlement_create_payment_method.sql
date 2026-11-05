-- Payment methods are company-wide data (D52): CASH is seeded; card, mobile money and integrated providers are
-- added by the administrator. Cash groups its expected amounts by method_code (a plain string, D52).
CREATE SCHEMA IF NOT EXISTS settlement;

-- Confirmed money facts are append-only (spec §8, D18 pattern): a correction is a new record (R3 refunds).
CREATE FUNCTION settlement.reject_fact_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TABLE settlement.payment_method
(
    id            UUID                     NOT NULL,
    code          VARCHAR(30)              NOT NULL,
    name          VARCHAR(100)             NOT NULL,
    kind          VARCHAR(20)              NOT NULL,
    provider_code VARCHAR(50),
    active        BOOLEAN                  NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL,
    CONSTRAINT payment_method_pk PRIMARY KEY (id),
    CONSTRAINT payment_method_code_uk UNIQUE (code),
    CONSTRAINT payment_method_code_ck CHECK (code ~ '^[A-Z0-9_]{1,30}$'),
    CONSTRAINT payment_method_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT payment_method_kind_ck CHECK (kind IN ('CASH', 'EXTERNAL_TERMINAL', 'INTEGRATED')),
    -- Only the seeded CASH method is of kind CASH: change is given in cash only (P12).
    CONSTRAINT payment_method_cash_ck CHECK ((kind = 'CASH') = (code = 'CASH')),
    -- An integrated method names the PaymentProvider that charges it (D53); the others have none.
    CONSTRAINT payment_method_provider_ck CHECK ((kind = 'INTEGRATED') = (provider_code IS NOT NULL))
);

INSERT INTO settlement.payment_method (id, code, name, kind, provider_code, active, created_at, updated_at, version)
VALUES ('0190a000-0000-7000-8000-00000000ca5e', 'CASH', 'Numerário', 'CASH', NULL, TRUE, now(), now(), 0);
