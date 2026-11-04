-- Catalog working copy (spec §5, §8.2, D31): the relational tables admins edit. The POS never reads them;
-- it reads the frozen release snapshot (V5002). Items are company-wide; prices are per store (one price list
-- per store). Store ids reference organization.store by id only (D6: no cross-module foreign keys).
CREATE SCHEMA IF NOT EXISTS catalog;

-- Families and subfamilies (one level: a subfamily's parent is a top-level family, checked in code).
CREATE TABLE catalog.family
(
    id         UUID                     NOT NULL,
    code       VARCHAR(30)              NOT NULL,
    name       VARCHAR(120)             NOT NULL,
    parent_id  UUID,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT family_pk PRIMARY KEY (id),
    CONSTRAINT family_code_uk UNIQUE (code),
    CONSTRAINT family_parent_fk FOREIGN KEY (parent_id) REFERENCES catalog.family (id),
    CONSTRAINT family_not_own_parent_ck CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT family_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT family_name_not_blank_ck CHECK (btrim(name) <> '')
);

CREATE TABLE catalog.unit
(
    id         UUID                     NOT NULL,
    code       VARCHAR(20)              NOT NULL,
    name       VARCHAR(60)              NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT unit_pk PRIMARY KEY (id),
    CONSTRAINT unit_code_uk UNIQUE (code),
    CONSTRAINT unit_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT unit_name_not_blank_ck CHECK (btrim(name) <> '')
);

-- Tax data is configuration; no country is seeded (CONTEXT.md).
CREATE TABLE catalog.tax_category
(
    id         UUID                     NOT NULL,
    code       VARCHAR(30)              NOT NULL,
    name       VARCHAR(120)             NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT tax_category_pk PRIMARY KEY (id),
    CONSTRAINT tax_category_code_uk UNIQUE (code),
    CONSTRAINT tax_category_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT tax_category_name_not_blank_ck CHECK (btrim(name) <> '')
);

-- A rate is a fact from an instant on: a change is a new row with a later effective_from, never an edit.
CREATE TABLE catalog.tax_rate
(
    id              UUID                     NOT NULL,
    tax_category_id UUID                     NOT NULL,
    rate_percent    NUMERIC(7, 4)            NOT NULL,
    effective_from  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT tax_rate_pk PRIMARY KEY (id),
    CONSTRAINT tax_rate_category_fk FOREIGN KEY (tax_category_id) REFERENCES catalog.tax_category (id),
    CONSTRAINT tax_rate_category_effective_uk UNIQUE (tax_category_id, effective_from),
    CONSTRAINT tax_rate_percent_range_ck CHECK (rate_percent >= 0 AND rate_percent <= 100)
);

CREATE TABLE catalog.item
(
    id                     UUID                     NOT NULL,
    code                   VARCHAR(30)              NOT NULL,
    name                   VARCHAR(120)             NOT NULL,
    family_id              UUID                     NOT NULL,
    unit_id                UUID                     NOT NULL,
    tax_category_id        UUID                     NOT NULL,
    production_destination VARCHAR(30),
    availability           VARCHAR(16)              NOT NULL,
    sold_by_weight         BOOLEAN                  NOT NULL,
    version                BIGINT                   NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT item_pk PRIMARY KEY (id),
    CONSTRAINT item_code_uk UNIQUE (code),
    CONSTRAINT item_family_fk FOREIGN KEY (family_id) REFERENCES catalog.family (id),
    CONSTRAINT item_unit_fk FOREIGN KEY (unit_id) REFERENCES catalog.unit (id),
    CONSTRAINT item_tax_category_fk FOREIGN KEY (tax_category_id) REFERENCES catalog.tax_category (id),
    CONSTRAINT item_availability_ck CHECK (availability IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT item_production_destination_ck CHECK (production_destination ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT item_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT item_name_not_blank_ck CHECK (btrim(name) <> '')
);
CREATE INDEX item_family_ix ON catalog.item (family_id);

CREATE TABLE catalog.item_variant
(
    id         UUID                     NOT NULL,
    item_id    UUID                     NOT NULL,
    code       VARCHAR(30)              NOT NULL,
    name       VARCHAR(120)             NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT item_variant_pk PRIMARY KEY (id),
    CONSTRAINT item_variant_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id),
    CONSTRAINT item_variant_item_code_uk UNIQUE (item_id, code),
    -- target of the composite foreign keys below: a variant reference always matches its item
    CONSTRAINT item_variant_id_item_uk UNIQUE (id, item_id),
    CONSTRAINT item_variant_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT item_variant_name_not_blank_ck CHECK (btrim(name) <> '')
);

-- Barcodes are unique in the installation; a barcode points at an item or at one of its variants.
CREATE TABLE catalog.barcode
(
    id         UUID                     NOT NULL,
    code       VARCHAR(64)              NOT NULL,
    item_id    UUID                     NOT NULL,
    variant_id UUID,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT barcode_pk PRIMARY KEY (id),
    CONSTRAINT barcode_code_uk UNIQUE (code),
    CONSTRAINT barcode_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id),
    CONSTRAINT barcode_variant_of_item_fk FOREIGN KEY (variant_id, item_id)
        REFERENCES catalog.item_variant (id, item_id),
    CONSTRAINT barcode_code_not_blank_ck CHECK (btrim(code) <> '')
);
CREATE INDEX barcode_item_ix ON catalog.barcode (item_id);

-- One price list per store (R1 simplification). Its currency is the company currency at creation.
CREATE TABLE catalog.price_list
(
    id         UUID                     NOT NULL,
    store_id   UUID                     NOT NULL,
    name       VARCHAR(120)             NOT NULL,
    currency   CHAR(3)                  NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT price_list_pk PRIMARY KEY (id),
    CONSTRAINT price_list_store_uk UNIQUE (store_id),
    CONSTRAINT price_list_id_currency_uk UNIQUE (id, currency),
    CONSTRAINT price_list_name_not_blank_ck CHECK (btrim(name) <> '')
);

-- Price of an item (variant_id NULL) or of one of its variants in a price list. Money (D15): the amount has
-- the currency's minor-unit scale (enforced by shared.Money); NUMERIC(19,4) holds any ISO currency.
CREATE TABLE catalog.item_price
(
    id            UUID                     NOT NULL,
    price_list_id UUID                     NOT NULL,
    item_id       UUID                     NOT NULL,
    variant_id    UUID,
    amount        NUMERIC(19, 4)           NOT NULL,
    currency      CHAR(3)                  NOT NULL,
    version       BIGINT                   NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT item_price_pk PRIMARY KEY (id),
    CONSTRAINT item_price_list_currency_fk FOREIGN KEY (price_list_id, currency)
        REFERENCES catalog.price_list (id, currency),
    CONSTRAINT item_price_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id),
    CONSTRAINT item_price_variant_of_item_fk FOREIGN KEY (variant_id, item_id)
        REFERENCES catalog.item_variant (id, item_id),
    CONSTRAINT item_price_list_item_variant_uk UNIQUE NULLS NOT DISTINCT (price_list_id, item_id, variant_id),
    CONSTRAINT item_price_amount_not_negative_ck CHECK (amount >= 0)
);
