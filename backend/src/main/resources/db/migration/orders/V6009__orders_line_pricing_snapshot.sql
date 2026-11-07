-- R4 pricing snapshot of an order line (D101–D104, D108, D109). ADD COLUMN never updates a row, so no confirmed line is
-- touched and order_line_confirmed_immutable keeps refusing every UPDATE of one: these columns are set at confirmation
-- only. An R3 line keeps list_unit_price NULL, read as its unit_price (no discount).
ALTER TABLE orders.order_line
    ADD COLUMN kind                 VARCHAR(10)   NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN automatic            BOOLEAN       NOT NULL DEFAULT false,
    ADD COLUMN list_unit_price      NUMERIC(19, 4),
    ADD COLUMN promotion_id         UUID,
    ADD COLUMN promotion_code       VARCHAR(30),
    ADD COLUMN promotion_name       VARCHAR(120),
    ADD COLUMN promotion_release_id UUID,
    ADD COLUMN promotion_version    BIGINT,
    ADD CONSTRAINT order_line_kind_ck CHECK (kind IN ('STANDARD', 'MENU', 'FEE')),
    -- D101: the effective price never exceeds the list price
    ADD CONSTRAINT order_line_effective_within_list_ck CHECK (list_unit_price IS NULL OR unit_price <= list_unit_price),
    ADD CONSTRAINT order_line_list_price_ck CHECK (list_unit_price IS NULL OR list_unit_price >= 0),
    -- an applied promotion is named whole, from the release it came from
    ADD CONSTRAINT order_line_promotion_ck CHECK (promotion_id IS NULL OR (
        promotion_code IS NOT NULL AND promotion_name IS NOT NULL AND promotion_release_id IS NOT NULL
            AND promotion_version IS NOT NULL AND list_unit_price IS NOT NULL));
