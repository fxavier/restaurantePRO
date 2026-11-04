-- The single company of this installation (spec §2, §8.2, D30). One schema per module (D6).
CREATE SCHEMA IF NOT EXISTS organization;

CREATE TABLE organization.company_profile
(
    -- Constant TRUE primary key: a second row is impossible (duplicate key or failed check).
    id         BOOLEAN                  NOT NULL DEFAULT TRUE,
    name       VARCHAR(200)             NOT NULL,
    tax_id     VARCHAR(50)              NOT NULL,
    country    VARCHAR(2)               NOT NULL,
    currency   VARCHAR(3)               NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT company_profile_pk PRIMARY KEY (id),
    CONSTRAINT company_profile_single_row_ck CHECK (id),
    CONSTRAINT company_profile_name_not_blank_ck CHECK (btrim(name) <> ''),
    CONSTRAINT company_profile_tax_id_not_blank_ck CHECK (btrim(tax_id) <> ''),
    CONSTRAINT company_profile_country_ck CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT company_profile_currency_ck CHECK (currency ~ '^[A-Z]{3}$')
);
