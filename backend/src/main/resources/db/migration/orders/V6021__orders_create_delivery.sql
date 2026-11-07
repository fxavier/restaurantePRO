-- R4 delivery (D127, D128). A store's delivery zones name the catalog FEE item a delivery order gets as an automatic
-- draft. A delivery is one per DELIVERY order (PK order_id): an address snapshot, frozen at dispatch, and its own
-- status, independent of the order's lifecycle, billing lock and fiscal state (§8.3). Its history is the append-only
-- delivery_event (§4). Customer, courier, store and fee item are other modules' ids (D6: no cross-module FK).
CREATE TABLE orders.delivery_zone
(
    id             UUID         NOT NULL,
    store_id       UUID         NOT NULL,
    code           VARCHAR(20)  NOT NULL,
    name           VARCHAR(80)  NOT NULL,
    fee_item_id    UUID         NOT NULL,
    fee_variant_id UUID,
    active         BOOLEAN      NOT NULL,
    version        BIGINT       NOT NULL,
    CONSTRAINT delivery_zone_pk PRIMARY KEY (id),
    CONSTRAINT delivery_zone_store_code_uk UNIQUE (store_id, code),
    CONSTRAINT delivery_zone_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT delivery_zone_name_not_blank_ck CHECK (btrim(name) <> '')
);

CREATE TABLE orders.delivery
(
    order_id            UUID                     NOT NULL,
    store_id            UUID                     NOT NULL,
    customer_id         UUID                     NOT NULL,
    -- null when the customer has no phone (the slip then shows none)
    phone_digits        VARCHAR(20),
    address_line1       VARCHAR(160)             NOT NULL,
    address_line2       VARCHAR(160),
    city                VARCHAR(80),
    postal_code         VARCHAR(20),
    address_notes       VARCHAR(300),
    zone_id             UUID                     NOT NULL,
    zone_code           VARCHAR(20)              NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    courier_employee_id UUID,
    promised_at         TIMESTAMP WITH TIME ZONE,
    dispatched_at       TIMESTAMP WITH TIME ZONE,
    delivered_at        TIMESTAMP WITH TIME ZONE,
    version             BIGINT                   NOT NULL,
    CONSTRAINT delivery_pk PRIMARY KEY (order_id),
    CONSTRAINT delivery_order_fk FOREIGN KEY (order_id) REFERENCES orders.order_header (id),
    CONSTRAINT delivery_zone_fk FOREIGN KEY (zone_id) REFERENCES orders.delivery_zone (id),
    CONSTRAINT delivery_status_ck
        CHECK (status IN ('PENDING', 'OUT_FOR_DELIVERY', 'DELIVERED', 'RETURNED', 'CANCELLED')),
    CONSTRAINT delivery_courier_ck
        CHECK (status NOT IN ('OUT_FOR_DELIVERY', 'DELIVERED') OR courier_employee_id IS NOT NULL),
    CONSTRAINT delivery_phone_digits_ck CHECK (phone_digits IS NULL OR phone_digits ~ '^[0-9]{4,20}$'),
    CONSTRAINT delivery_address_not_blank_ck CHECK (btrim(address_line1) <> ''),
    CONSTRAINT delivery_delivered_at_ck CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL))
);

CREATE INDEX delivery_store_status_ix ON orders.delivery (store_id, status);

CREATE TABLE orders.delivery_event
(
    id                  UUID                     NOT NULL,
    order_id            UUID                     NOT NULL,
    from_status         VARCHAR(20),
    to_status           VARCHAR(20)              NOT NULL,
    at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_employee_id   UUID                     NOT NULL,
    courier_employee_id UUID,
    reason              VARCHAR(300),
    CONSTRAINT delivery_event_pk PRIMARY KEY (id),
    CONSTRAINT delivery_event_delivery_fk FOREIGN KEY (order_id) REFERENCES orders.delivery (order_id),
    CONSTRAINT delivery_event_from_ck
        CHECK (from_status IS NULL OR from_status IN ('PENDING', 'OUT_FOR_DELIVERY', 'DELIVERED', 'RETURNED',
                                                     'CANCELLED')),
    CONSTRAINT delivery_event_to_ck
        CHECK (to_status IN ('PENDING', 'OUT_FOR_DELIVERY', 'DELIVERED', 'RETURNED', 'CANCELLED')),
    -- a return names why
    CONSTRAINT delivery_event_return_reason_ck CHECK (to_status <> 'RETURNED' OR btrim(reason) <> '')
);

CREATE INDEX delivery_event_order_ix ON orders.delivery_event (order_id, at);

CREATE TRIGGER delivery_event_immutable
    BEFORE UPDATE OR DELETE ON orders.delivery_event
    FOR EACH ROW EXECUTE FUNCTION orders.reject_fact_change();
CREATE TRIGGER delivery_event_no_truncate
    BEFORE TRUNCATE ON orders.delivery_event
    FOR EACH STATEMENT EXECUTE FUNCTION orders.reject_fact_change();
