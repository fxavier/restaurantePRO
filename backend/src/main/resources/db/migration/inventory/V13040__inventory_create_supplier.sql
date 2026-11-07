-- Suppliers (D123, spec §5 "Fornecedores"): company-wide, no store. Configuration, not facts.
CREATE TABLE inventory.supplier
(
    id         UUID                     NOT NULL,
    code       VARCHAR(40)              NOT NULL,
    name       VARCHAR(160)             NOT NULL,
    tax_id     VARCHAR(40),
    email      VARCHAR(200),
    phone      VARCHAR(40),
    notes      TEXT,
    active     BOOLEAN                  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT supplier_pk PRIMARY KEY (id),
    CONSTRAINT supplier_code_uk UNIQUE (code),
    CONSTRAINT supplier_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT supplier_name_ck CHECK (btrim(name) <> '')
);

-- What a supplier sells of a stock item, per pack (none = the base unit): its code and the last cost per base unit,
-- which each confirmed receipt updates (D123; the receipt line is the fact, this is configuration).
CREATE TABLE inventory.supplier_item
(
    id             UUID                     NOT NULL,
    supplier_id    UUID                     NOT NULL,
    stock_item_id  UUID                     NOT NULL,
    pack_id        UUID,
    supplier_code  VARCHAR(60),
    last_unit_cost NUMERIC(19, 6),
    last_cost_at   TIMESTAMP WITH TIME ZONE,
    version        BIGINT                   NOT NULL,
    CONSTRAINT supplier_item_pk PRIMARY KEY (id),
    CONSTRAINT supplier_item_supplier_fk FOREIGN KEY (supplier_id) REFERENCES inventory.supplier (id),
    CONSTRAINT supplier_item_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT supplier_item_pack_fk FOREIGN KEY (pack_id) REFERENCES inventory.stock_item_pack (id),
    CONSTRAINT supplier_item_cost_ck CHECK (last_unit_cost IS NULL OR last_unit_cost >= 0),
    CONSTRAINT supplier_item_cost_at_ck CHECK ((last_unit_cost IS NULL) = (last_cost_at IS NULL))
);
CREATE UNIQUE INDEX supplier_item_uk ON inventory.supplier_item
    (supplier_id, stock_item_id, coalesce(pack_id, '00000000-0000-0000-0000-000000000000'::uuid));
