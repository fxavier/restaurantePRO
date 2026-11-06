-- R4 configuration that composes lines (D109). It belongs to the release: publication freezes it into the snapshot
-- (schema version 2). store_id NULL means every store (no cross-module FK to organization, D6).

-- A macro: a named list of item/variant/quantity added as drafts together. Company-wide.
CREATE TABLE catalog.macro
(
    id         UUID                     NOT NULL,
    code       VARCHAR(30)              NOT NULL,
    name       VARCHAR(120)             NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT macro_pk PRIMARY KEY (id),
    CONSTRAINT macro_code_uk UNIQUE (code),
    CONSTRAINT macro_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT macro_name_not_blank_ck CHECK (btrim(name) <> '')
);

CREATE TABLE catalog.macro_entry
(
    macro_id   UUID           NOT NULL,
    position   INTEGER        NOT NULL,
    item_id    UUID           NOT NULL,
    variant_id UUID,
    quantity   NUMERIC(12, 3) NOT NULL,
    CONSTRAINT macro_entry_pk PRIMARY KEY (macro_id, position),
    CONSTRAINT macro_entry_macro_fk FOREIGN KEY (macro_id) REFERENCES catalog.macro (id) ON DELETE CASCADE,
    CONSTRAINT macro_entry_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id),
    CONSTRAINT macro_entry_variant_of_item_fk FOREIGN KEY (variant_id, item_id)
        REFERENCES catalog.item_variant (id, item_id),
    CONSTRAINT macro_entry_quantity_ck CHECK (quantity > 0)
);
CREATE INDEX macro_entry_item_ix ON catalog.macro_entry (item_id);

CREATE TABLE catalog.quick_page
(
    id         UUID                     NOT NULL,
    store_id   UUID,
    code       VARCHAR(30)              NOT NULL,
    name       VARCHAR(120)             NOT NULL,
    position   INTEGER                  NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT quick_page_pk PRIMARY KEY (id),
    CONSTRAINT quick_page_store_code_uk UNIQUE NULLS NOT DISTINCT (store_id, code),
    CONSTRAINT quick_page_position_ck CHECK (position >= 0),
    CONSTRAINT quick_page_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT quick_page_name_not_blank_ck CHECK (btrim(name) <> '')
);

-- An entry is exactly one of an item, a variant or a macro.
CREATE TABLE catalog.quick_page_entry
(
    page_id    UUID    NOT NULL,
    position   INTEGER NOT NULL,
    item_id    UUID,
    variant_id UUID,
    macro_id   UUID,
    CONSTRAINT quick_page_entry_pk PRIMARY KEY (page_id, position),
    CONSTRAINT quick_page_entry_page_fk FOREIGN KEY (page_id) REFERENCES catalog.quick_page (id) ON DELETE CASCADE,
    CONSTRAINT quick_page_entry_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id),
    CONSTRAINT quick_page_entry_variant_fk FOREIGN KEY (variant_id) REFERENCES catalog.item_variant (id),
    CONSTRAINT quick_page_entry_macro_fk FOREIGN KEY (macro_id) REFERENCES catalog.macro (id),
    CONSTRAINT quick_page_entry_exactly_one_ck CHECK (num_nonnulls(item_id, variant_id, macro_id) = 1)
);

-- TABLE_OPEN_PER_GUEST: quantity × guests when a table opens (a cover charge); ORDER_OPEN: when an order of the mode
-- opens (mode NULL = every mode). Only ORDER_OPEN names a mode. Order modes belong to orders: only the format is checked.
CREATE TABLE catalog.automatic_item
(
    id         UUID                     NOT NULL,
    store_id   UUID,
    trigger    VARCHAR(30)              NOT NULL,
    mode       VARCHAR(20),
    item_id    UUID                     NOT NULL,
    variant_id UUID,
    quantity   NUMERIC(12, 3)           NOT NULL,
    active     BOOLEAN                  NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT automatic_item_pk PRIMARY KEY (id),
    CONSTRAINT automatic_item_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id),
    CONSTRAINT automatic_item_variant_of_item_fk FOREIGN KEY (variant_id, item_id)
        REFERENCES catalog.item_variant (id, item_id),
    CONSTRAINT automatic_item_trigger_ck CHECK (trigger IN ('TABLE_OPEN_PER_GUEST', 'ORDER_OPEN')),
    CONSTRAINT automatic_item_mode_ck CHECK (mode IS NULL OR (trigger = 'ORDER_OPEN' AND mode ~ '^[A-Z][A-Z_]*$')),
    CONSTRAINT automatic_item_quantity_ck CHECK (quantity > 0)
);
CREATE INDEX automatic_item_store_ix ON catalog.automatic_item (store_id);
