-- R4 menus (D108, P50): an item has a kind; a MENU item has component groups whose options are other items (or
-- variants). Only the menu line is billed; components carry no amount. Release validation (code) checks that every
-- option is an ACTIVE, non-MENU item and that only MENU items have groups; the snapshot (schema version 2) freezes them.
ALTER TABLE catalog.item
    ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'STANDARD',
    ADD CONSTRAINT item_kind_ck CHECK (kind IN ('STANDARD', 'MENU', 'FEE'));

CREATE TABLE catalog.menu_group
(
    id          UUID                     NOT NULL,
    item_id     UUID                     NOT NULL,
    code        VARCHAR(30)              NOT NULL,
    name        VARCHAR(120)             NOT NULL,
    min_choices INTEGER                  NOT NULL,
    max_choices INTEGER                  NOT NULL,
    position    INTEGER                  NOT NULL,
    version     BIGINT                   NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT menu_group_pk PRIMARY KEY (id),
    CONSTRAINT menu_group_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id),
    CONSTRAINT menu_group_item_code_uk UNIQUE (item_id, code),
    CONSTRAINT menu_group_min_choices_ck CHECK (min_choices >= 0),
    CONSTRAINT menu_group_max_choices_ck CHECK (max_choices >= min_choices),
    CONSTRAINT menu_group_position_ck CHECK (position >= 0),
    CONSTRAINT menu_group_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT menu_group_name_not_blank_ck CHECK (btrim(name) <> '')
);

-- The options of a group, in order. Replaced as a whole with the group, so a reorder may swap two options in place:
-- the uniqueness is checked at commit. A variant option always belongs to its item (composite FK, as barcodes).
CREATE TABLE catalog.menu_option
(
    group_id   UUID           NOT NULL,
    position   INTEGER        NOT NULL,
    item_id    UUID           NOT NULL,
    variant_id UUID,
    quantity   NUMERIC(12, 3) NOT NULL,
    CONSTRAINT menu_option_pk PRIMARY KEY (group_id, position),
    CONSTRAINT menu_option_group_fk FOREIGN KEY (group_id) REFERENCES catalog.menu_group (id) ON DELETE CASCADE,
    CONSTRAINT menu_option_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id),
    CONSTRAINT menu_option_variant_of_item_fk FOREIGN KEY (variant_id, item_id)
        REFERENCES catalog.item_variant (id, item_id),
    CONSTRAINT menu_option_group_item_variant_uk UNIQUE NULLS NOT DISTINCT (group_id, item_id, variant_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT menu_option_quantity_ck CHECK (quantity > 0)
);
CREATE INDEX menu_option_item_ix ON catalog.menu_option (item_id);
