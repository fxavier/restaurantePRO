-- Contacts of a customer (spec §5 "Contactos normalizados", D72). A phone is stored as its digits; its reverse
-- serves the suffix search ("…4567") as a prefix scan on a text_pattern_ops B-tree.
CREATE TABLE customers.customer_phone
(
    id              UUID        NOT NULL,
    customer_id     UUID        NOT NULL,
    digits          VARCHAR(20) NOT NULL,
    digits_reversed TEXT GENERATED ALWAYS AS (reverse(digits)) STORED,
    label           VARCHAR(40),
    is_primary      BOOLEAN     NOT NULL,
    CONSTRAINT customer_phone_pk PRIMARY KEY (id),
    CONSTRAINT customer_phone_customer_fk FOREIGN KEY (customer_id) REFERENCES customers.customer (id),
    CONSTRAINT customer_phone_digits_ck CHECK (digits ~ '^[0-9]{4,20}$'),
    CONSTRAINT customer_phone_customer_digits_uk UNIQUE (customer_id, digits)
);
CREATE INDEX customer_phone_digits_reversed_ix ON customers.customer_phone (digits_reversed text_pattern_ops);
-- at most one primary phone per customer
CREATE UNIQUE INDEX customer_phone_primary_uk ON customers.customer_phone (customer_id) WHERE is_primary;

-- BILLING goes on documents (the CustomerSnapshot); DELIVERY is used by delivery orders (R4).
CREATE TABLE customers.customer_address
(
    id          UUID         NOT NULL,
    customer_id UUID         NOT NULL,
    kind        VARCHAR(10)  NOT NULL,
    line1       VARCHAR(200) NOT NULL,
    line2       VARCHAR(200),
    city        VARCHAR(100),
    postal_code VARCHAR(20),
    country     VARCHAR(60),
    notes       TEXT,
    version     BIGINT       NOT NULL,
    CONSTRAINT customer_address_pk PRIMARY KEY (id),
    CONSTRAINT customer_address_customer_fk FOREIGN KEY (customer_id) REFERENCES customers.customer (id),
    CONSTRAINT customer_address_kind_ck CHECK (kind IN ('BILLING', 'DELIVERY')),
    CONSTRAINT customer_address_line1_not_blank_ck CHECK (btrim(line1) <> '')
);
CREATE INDEX customer_address_customer_ix ON customers.customer_address (customer_id);
