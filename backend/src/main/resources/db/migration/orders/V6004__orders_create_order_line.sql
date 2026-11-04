-- An order line (spec §8.2, §8.3 OrderLine DRAFT -> CONFIRMED; §8.4: price and tax fixed at confirmation).
-- A DRAFT is not a fact: it may be edited or deleted. A CONFIRMED line carries the catalog snapshot and is immutable
-- (trigger in V6007); its net quantity = quantity − Σ voids − Σ transferred out is never stored.
CREATE TABLE orders.order_line
(
    id                       UUID                     NOT NULL,
    order_id                 UUID                     NOT NULL,
    status                   VARCHAR(10)              NOT NULL,
    item_id                  UUID                     NOT NULL,
    variant_id               UUID,
    quantity                 NUMERIC(12, 3)           NOT NULL,
    notes                    VARCHAR(200),
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by_employee_id   UUID                     NOT NULL,
    -- the snapshot, set at confirmation (D58)
    item_code                VARCHAR(30),
    description              VARCHAR(120),
    variant_name             VARCHAR(120),
    unit_price               NUMERIC(19, 4),
    currency                 CHAR(3),
    tax_category_id          UUID,
    tax_code                 VARCHAR(30),
    tax_rate                 NUMERIC(7, 4),
    release_id               UUID,
    catalog_version          BIGINT,
    destination              VARCHAR(30),
    confirmed_at             TIMESTAMP WITH TIME ZONE,
    confirmed_by_employee_id UUID,
    -- exactly one origin once confirmed: a round, or a transfer from another order (S14)
    batch_id                 UUID,
    origin_transfer_id       UUID,
    version                  BIGINT                   NOT NULL,
    CONSTRAINT order_line_pk PRIMARY KEY (id),
    CONSTRAINT order_line_order_fk FOREIGN KEY (order_id) REFERENCES orders.order_header (id),
    CONSTRAINT order_line_batch_same_order_fk FOREIGN KEY (batch_id, order_id)
        REFERENCES orders.order_batch (id, order_id),
    CONSTRAINT order_line_origin_transfer_same_order_fk FOREIGN KEY (origin_transfer_id, order_id)
        REFERENCES orders.order_transfer (id, target_order_id),
    CONSTRAINT order_line_status_ck CHECK (status IN ('DRAFT', 'CONFIRMED')),
    CONSTRAINT order_line_quantity_positive_ck CHECK (quantity > 0),
    CONSTRAINT order_line_unit_price_ck CHECK (unit_price IS NULL OR unit_price >= 0),
    CONSTRAINT order_line_tax_rate_ck CHECK (tax_rate IS NULL OR tax_rate >= 0),
    -- variant_name and destination stay nullable: an item without a variant, an item nothing prepares (D56)
    CONSTRAINT order_line_confirmed_snapshot_ck CHECK (status = 'DRAFT' OR (
        item_code IS NOT NULL AND description IS NOT NULL AND unit_price IS NOT NULL AND currency IS NOT NULL
            AND tax_category_id IS NOT NULL AND tax_code IS NOT NULL AND tax_rate IS NOT NULL
            AND release_id IS NOT NULL AND catalog_version IS NOT NULL AND confirmed_at IS NOT NULL)),
    CONSTRAINT order_line_draft_has_no_origin_ck CHECK (status = 'CONFIRMED' OR (
        batch_id IS NULL AND origin_transfer_id IS NULL AND confirmed_at IS NULL)),
    CONSTRAINT order_line_one_origin_ck CHECK (status = 'DRAFT' OR ((batch_id IS NULL) <> (origin_transfer_id IS NULL)))
);

-- lines of an order by status (drafts to confirm, confirmed lines to bill)
CREATE INDEX order_line_order_status_ix ON orders.order_line (order_id, status);
CREATE INDEX order_line_batch_ix ON orders.order_line (batch_id) WHERE batch_id IS NOT NULL;
