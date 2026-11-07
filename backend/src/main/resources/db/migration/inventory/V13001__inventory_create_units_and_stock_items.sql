-- Inventory (spec §5 "Stock, receitas e compras", §8.1; D113, D114). One schema, no cross-module foreign keys (D6).
CREATE SCHEMA IF NOT EXISTS inventory;

-- Facts are append-only (§8): every fact table of this module refuses UPDATE, DELETE and TRUNCATE with this.
CREATE FUNCTION inventory.reject_fact_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- The stock units (D114): a seeded, code-defined set per dimension with the factor to the dimension's base
-- (g, ml, un). Converting across dimensions is refused (UNIT_DIMENSION_MISMATCH).
CREATE TABLE inventory.stock_unit
(
    code           VARCHAR(10)    NOT NULL,
    dimension      VARCHAR(10)    NOT NULL,
    factor_to_base NUMERIC(19, 9) NOT NULL,
    CONSTRAINT stock_unit_pk PRIMARY KEY (code),
    CONSTRAINT stock_unit_dimension_ck CHECK (dimension IN ('MASS', 'VOLUME', 'COUNT')),
    CONSTRAINT stock_unit_factor_ck CHECK (factor_to_base > 0)
);

INSERT INTO inventory.stock_unit (code, dimension, factor_to_base)
VALUES ('g', 'MASS', 1),
       ('kg', 'MASS', 1000),
       ('ml', 'VOLUME', 1),
       ('l', 'VOLUME', 1000),
       ('un', 'COUNT', 1);

-- A stock item is company-wide (D114, P38): its code is unique across stores, and every quantity of it in the
-- ledger is in its base unit.
CREATE TABLE inventory.stock_item
(
    id         UUID                     NOT NULL,
    code       VARCHAR(40)              NOT NULL,
    name       VARCHAR(160)             NOT NULL,
    base_unit  VARCHAR(10)              NOT NULL,
    kind       VARCHAR(10)              NOT NULL,
    active     BOOLEAN                  NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT stock_item_pk PRIMARY KEY (id),
    CONSTRAINT stock_item_code_uk UNIQUE (code),
    CONSTRAINT stock_item_base_unit_fk FOREIGN KEY (base_unit) REFERENCES inventory.stock_unit (code),
    CONSTRAINT stock_item_kind_ck CHECK (kind IN ('PURCHASED', 'PREPARED')),
    CONSTRAINT stock_item_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT stock_item_name_ck CHECK (btrim(name) <> '')
);

-- A pack converts purchase quantities ("caixa 12 un", "saco 25 kg") into the item's base unit.
CREATE TABLE inventory.stock_item_pack
(
    id            UUID           NOT NULL,
    stock_item_id UUID           NOT NULL,
    code          VARCHAR(40)    NOT NULL,
    name          VARCHAR(160)   NOT NULL,
    quantity_base NUMERIC(19, 6) NOT NULL,
    CONSTRAINT stock_item_pack_pk PRIMARY KEY (id),
    CONSTRAINT stock_item_pack_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT stock_item_pack_code_uk UNIQUE (stock_item_id, code),
    CONSTRAINT stock_item_pack_quantity_ck CHECK (quantity_base > 0)
);
