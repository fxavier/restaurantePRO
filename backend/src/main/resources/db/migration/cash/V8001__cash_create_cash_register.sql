-- Cash registers of a store (spec §4 "caixas", CONTEXT.md CashRegister). One schema per module (D6); store_id
-- refers to organization.store without a foreign key, existence is checked through OrganizationApi.
CREATE SCHEMA IF NOT EXISTS cash;

CREATE TABLE cash.cash_register
(
    id         UUID                     NOT NULL,
    store_id   UUID                     NOT NULL,
    code       VARCHAR(20)              NOT NULL,
    name       VARCHAR(120)             NOT NULL,
    active     BOOLEAN                  NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT cash_register_pk PRIMARY KEY (id),
    -- also the index for "registers of a store" (leading store_id)
    CONSTRAINT cash_register_store_code_uk UNIQUE (store_id, code),
    CONSTRAINT cash_register_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT cash_register_name_not_blank_ck CHECK (btrim(name) <> '')
);
