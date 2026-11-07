-- R4 operational availability (D109): changes during service, changes no price, and is outside the release, so no
-- republication is needed. Store ids reference organization.store by id only (D6).

-- A row exists while the item (variant_id NULL: the item and all its variants) or one variant is sold out in the
-- store; marking it available again deletes the row (the audit log keeps the history). One row per store × key:
-- concurrent "sold out" requests insert with ON CONFLICT DO NOTHING against the unique index below.
CREATE TABLE catalog.item_availability
(
    id                     UUID                     NOT NULL DEFAULT uuidv7(),
    store_id               UUID                     NOT NULL,
    item_id                UUID                     NOT NULL,
    variant_id             UUID,
    sold_out_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    sold_out_by_actor_kind VARCHAR(16)              NOT NULL,
    sold_out_by_actor_id   UUID                     NOT NULL,
    reason                 VARCHAR(200),
    CONSTRAINT item_availability_pk PRIMARY KEY (id),
    CONSTRAINT item_availability_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id),
    CONSTRAINT item_availability_variant_of_item_fk FOREIGN KEY (variant_id, item_id)
        REFERENCES catalog.item_variant (id, item_id),
    CONSTRAINT item_availability_actor_kind_ck CHECK (sold_out_by_actor_kind IN ('ACCOUNT', 'EMPLOYEE'))
);
CREATE UNIQUE INDEX item_availability_store_item_variant_ux
    ON catalog.item_availability (store_id, item_id, coalesce(variant_id, '00000000-0000-0000-0000-000000000000'));

-- The daily dishes of a store for a business date, in order. Each must be in the store's published release (code).
CREATE TABLE catalog.daily_dish
(
    store_id      UUID    NOT NULL,
    business_date DATE    NOT NULL,
    item_id       UUID    NOT NULL,
    position      INTEGER NOT NULL,
    CONSTRAINT daily_dish_pk PRIMARY KEY (store_id, business_date, item_id),
    CONSTRAINT daily_dish_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id),
    CONSTRAINT daily_dish_position_ck CHECK (position >= 0)
);
