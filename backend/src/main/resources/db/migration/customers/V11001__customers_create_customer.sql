-- Customers (spec §5 "Clientes e crédito", §8.1; D64, D72). One schema per module (D6). Customers are company-wide
-- (P38): no store_id.
CREATE SCHEMA IF NOT EXISTS customers;

-- pg_trgm is the only index type that serves a "name contains" search (D72; trusted since PG13). It ships in the
-- contrib of the postgres image.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- The search form of a text: lower case, with a fixed accent map (D72: unaccent is avoided). The generated column
-- and the search query both use it, so they always fold the same way.
CREATE FUNCTION customers.fold(value TEXT) RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    RETURN lower(translate(value,
                           'ÁÀÂÃÄÅáàâãäåÉÈÊËéèêëÍÌÎÏíìîïÓÒÔÕÖóòôõöÚÙÛÜúùûüÇçÑñÝýÿ',
                           'AAAAAAaaaaaaEEEEeeeeIIIIiiiiOOOOOoooooUUUUuuuuCcNnYyy'));

CREATE TABLE customers.customer
(
    id          UUID                     NOT NULL,
    name        VARCHAR(160)             NOT NULL,
    name_search TEXT GENERATED ALWAYS AS (customers.fold(name)) STORED,
    -- not unique: a family can share one (r3-decisions §3)
    tax_id      VARCHAR(30),
    email       VARCHAR(254),
    notes       TEXT,
    active      BOOLEAN                  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT customer_pk PRIMARY KEY (id),
    CONSTRAINT customer_name_not_blank_ck CHECK (btrim(name) <> ''),
    CONSTRAINT customer_tax_id_not_blank_ck CHECK (tax_id IS NULL OR btrim(tax_id) <> '')
);
-- fastupdate off: a new customer goes straight into the index instead of a pending list that every search would
-- scan (and that the planner prices as such, preferring a sequential scan until VACUUM flushes it). Customers are
-- written rarely and searched at every till, so the slower insert is the right trade.
CREATE INDEX customer_name_search_trgm_ix ON customers.customer USING gin (name_search gin_trgm_ops)
    WITH (fastupdate = off);
CREATE INDEX customer_tax_id_ix ON customers.customer (tax_id);
