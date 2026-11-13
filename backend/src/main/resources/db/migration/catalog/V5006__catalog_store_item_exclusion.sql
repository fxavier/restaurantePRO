-- R5 per-store availability (D146, ADR 0036): every active item is in every store unless excluded there. An excluded
-- item is left out of that store's next release and needs no price in its price list. No row = R4 behaviour, so no
-- data migration (D148). Store ids reference organization.store by id only (D6).
CREATE TABLE catalog.store_item_exclusion
(
    store_id               UUID                     NOT NULL,
    item_id                UUID                     NOT NULL,
    reason                 VARCHAR(200),
    excluded_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    excluded_by_account_id UUID                     NOT NULL,
    CONSTRAINT store_item_exclusion_pk PRIMARY KEY (store_id, item_id),
    CONSTRAINT store_item_exclusion_item_fk FOREIGN KEY (item_id) REFERENCES catalog.item (id)
);
CREATE INDEX store_item_exclusion_item_ix ON catalog.store_item_exclusion (item_id);

-- The warnings of the last validation (D147), so the publish screen shows them. Set at validation, before any
-- snapshot; the immutability trigger of a published release does not cover it.
ALTER TABLE catalog.configuration_release
    ADD COLUMN validation_warnings JSONB;
