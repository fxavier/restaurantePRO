-- A confirmed Payment and its allocation to the order (spec §8.2, §8.3; r2-decisions §4 settlement). Facts: the
-- database refuses UPDATE, DELETE and TRUNCATE. One payment per attempt (UNIQUE attempt_id), and in R2 exactly one
-- allocation per payment, of the full amount, to the order (R3 adds partitions and documents).
CREATE TABLE settlement.payment
(
    id                       UUID                     NOT NULL,
    attempt_id               UUID                     NOT NULL,
    order_id                 UUID                     NOT NULL,
    store_id                 UUID                     NOT NULL,
    session_id               UUID                     NOT NULL,
    method_code              VARCHAR(30)              NOT NULL,
    amount                   NUMERIC(19, 4)           NOT NULL,
    currency                 CHAR(3)                  NOT NULL,
    status                   VARCHAR(10)              NOT NULL,
    collected_by_employee_id UUID                     NOT NULL,
    confirmed_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payment_pk PRIMARY KEY (id),
    CONSTRAINT payment_attempt_fk FOREIGN KEY (attempt_id) REFERENCES settlement.payment_attempt (id),
    CONSTRAINT payment_attempt_uk UNIQUE (attempt_id),
    CONSTRAINT payment_status_ck CHECK (status = 'CONFIRMED'),
    CONSTRAINT payment_amount_ck CHECK (amount > 0)
);
CREATE INDEX payment_order_ix ON settlement.payment (order_id);

CREATE TABLE settlement.payment_allocation
(
    id          UUID                     NOT NULL,
    payment_id  UUID                     NOT NULL,
    target_type VARCHAR(10)              NOT NULL,
    target_id   UUID                     NOT NULL,
    amount      NUMERIC(19, 4)           NOT NULL,
    currency    CHAR(3)                  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payment_allocation_pk PRIMARY KEY (id),
    CONSTRAINT payment_allocation_payment_fk FOREIGN KEY (payment_id) REFERENCES settlement.payment (id),
    CONSTRAINT payment_allocation_payment_uk UNIQUE (payment_id),
    CONSTRAINT payment_allocation_target_ck CHECK (target_type = 'ORDER'),
    CONSTRAINT payment_allocation_amount_ck CHECK (amount > 0)
);

CREATE TRIGGER payment_append_only
    BEFORE UPDATE OR DELETE
    ON settlement.payment
    FOR EACH ROW
EXECUTE FUNCTION settlement.reject_fact_change();

CREATE TRIGGER payment_no_truncate
    BEFORE TRUNCATE
    ON settlement.payment
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();

CREATE TRIGGER payment_allocation_append_only
    BEFORE UPDATE OR DELETE
    ON settlement.payment_allocation
    FOR EACH ROW
EXECUTE FUNCTION settlement.reject_fact_change();

CREATE TRIGGER payment_allocation_no_truncate
    BEFORE TRUNCATE
    ON settlement.payment_allocation
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();
