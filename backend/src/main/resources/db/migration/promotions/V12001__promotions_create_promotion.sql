-- Promotions working copy (D102, P43): company-wide line-level promotions, assigned to stores, with their scope and
-- time windows. Only a published promotion release (V12003) is ever read for pricing; edits here change nothing
-- until the next publication.
CREATE SCHEMA IF NOT EXISTS promotions;

CREATE TABLE promotions.promotion
(
    id           UUID                     NOT NULL,
    code         VARCHAR(40)              NOT NULL,
    name         VARCHAR(120)             NOT NULL,
    kind         VARCHAR(20)              NOT NULL,
    percent      NUMERIC(5, 2),
    amount       NUMERIC(19, 4),
    currency     CHAR(3),
    priority     INTEGER                  NOT NULL,
    valid_from   TIMESTAMP WITH TIME ZONE,
    valid_to     TIMESTAMP WITH TIME ZONE,
    members_only BOOLEAN                  NOT NULL,
    modes        TEXT[],
    active       BOOLEAN                  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT                   NOT NULL,
    CONSTRAINT promotion_pk PRIMARY KEY (id),
    CONSTRAINT promotion_code_uk UNIQUE (code),
    CONSTRAINT promotion_kind_ck CHECK (kind IN ('PERCENT_OFF', 'AMOUNT_OFF_UNIT', 'FIXED_UNIT_PRICE')),
    CONSTRAINT promotion_percent_off_ck CHECK (kind <> 'PERCENT_OFF'
        OR (percent > 0 AND percent <= 100 AND amount IS NULL AND currency IS NULL)),
    CONSTRAINT promotion_amount_off_ck CHECK (kind <> 'AMOUNT_OFF_UNIT'
        OR (amount > 0 AND currency IS NOT NULL AND percent IS NULL)),
    CONSTRAINT promotion_fixed_price_ck CHECK (kind <> 'FIXED_UNIT_PRICE'
        OR (amount >= 0 AND currency IS NOT NULL AND percent IS NULL)),
    CONSTRAINT promotion_validity_ck CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from < valid_to),
    CONSTRAINT promotion_modes_ck CHECK (modes IS NULL OR cardinality(modes) > 0)
);

-- What a promotion applies to: exactly one of an item, a variant or a family per row.
CREATE TABLE promotions.promotion_scope
(
    promotion_id UUID    NOT NULL,
    position     INTEGER NOT NULL,
    item_id      UUID,
    variant_id   UUID,
    family_id    UUID,
    CONSTRAINT promotion_scope_pk PRIMARY KEY (promotion_id, position),
    CONSTRAINT promotion_scope_promotion_fk FOREIGN KEY (promotion_id)
        REFERENCES promotions.promotion (id) ON DELETE CASCADE,
    CONSTRAINT promotion_scope_exactly_one_ck CHECK (num_nonnulls(item_id, variant_id, family_id) = 1),
    CONSTRAINT promotion_scope_uk UNIQUE NULLS NOT DISTINCT (promotion_id, item_id, variant_id, family_id)
);

-- Happy-hour windows in the store's zone; days are ISO numbers (1 = Monday) of the day a window starts on. A
-- window whose end is before its start crosses midnight.
CREATE TABLE promotions.promotion_window
(
    promotion_id UUID       NOT NULL,
    position     INTEGER    NOT NULL,
    days_of_week SMALLINT[] NOT NULL,
    local_start  TIME       NOT NULL,
    local_end    TIME       NOT NULL,
    CONSTRAINT promotion_window_pk PRIMARY KEY (promotion_id, position),
    CONSTRAINT promotion_window_promotion_fk FOREIGN KEY (promotion_id)
        REFERENCES promotions.promotion (id) ON DELETE CASCADE,
    CONSTRAINT promotion_window_days_ck CHECK (cardinality(days_of_week) > 0
        AND days_of_week <@ ARRAY [1, 2, 3, 4, 5, 6, 7]::SMALLINT[]),
    CONSTRAINT promotion_window_not_empty_ck CHECK (local_start <> local_end)
);

-- The stores a promotion is published to (no cross-module FK, D6).
CREATE TABLE promotions.promotion_store
(
    promotion_id UUID NOT NULL,
    store_id     UUID NOT NULL,
    CONSTRAINT promotion_store_pk PRIMARY KEY (promotion_id, store_id),
    CONSTRAINT promotion_store_promotion_fk FOREIGN KEY (promotion_id)
        REFERENCES promotions.promotion (id) ON DELETE CASCADE
);

CREATE INDEX promotion_store_store_ix ON promotions.promotion_store (store_id);
