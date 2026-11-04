-- Room areas of a store (spec §8.2, CONTEXT.md RoomArea). One schema per module (D6); store_id refers to
-- organization.store without a foreign key, existence is checked through OrganizationApi.
CREATE SCHEMA IF NOT EXISTS dining;

CREATE TABLE dining.room_area
(
    id         UUID                     NOT NULL,
    store_id   UUID                     NOT NULL,
    code       VARCHAR(20)              NOT NULL,
    name       VARCHAR(120)             NOT NULL,
    active     BOOLEAN                  NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT room_area_pk PRIMARY KEY (id),
    -- also the index for "areas of a store" (leading store_id)
    CONSTRAINT room_area_store_code_uk UNIQUE (store_id, code),
    -- target of restaurant_table_area_same_store_fk
    CONSTRAINT room_area_id_store_uk UNIQUE (id, store_id),
    CONSTRAINT room_area_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT room_area_name_not_blank_ck CHECK (btrim(name) <> '')
);
