-- Reporting projections of money and voids (D84, D85, D87). Money in and out of the till only: payments (signed
-- positive) and refunds (signed negative), each in the cash day of its session. Credit never appears as money
-- received (spec §14 #4): it is a separate projection, credit_record, keyed by the posted charge.
CREATE TABLE reporting.payment_record
(
    source_type   VARCHAR(10)              NOT NULL,
    source_id     UUID                     NOT NULL,
    store_id      UUID                     NOT NULL,
    business_date DATE                     NOT NULL,
    local_hour    SMALLINT                 NOT NULL,
    method_code   VARCHAR(30)              NOT NULL,
    signed_amount NUMERIC(19, 4)           NOT NULL,
    target_type   VARCHAR(15)              NOT NULL,
    order_id      UUID,
    customer_id   UUID,
    employee_id   UUID,
    terminal_id   UUID,
    projected_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payment_record_pk PRIMARY KEY (source_type, source_id),
    CONSTRAINT payment_record_source_type_ck CHECK (source_type IN ('PAYMENT', 'REFUND')),
    CONSTRAINT payment_record_sign_ck CHECK ((source_type = 'PAYMENT' AND signed_amount > 0)
        OR (source_type = 'REFUND' AND signed_amount < 0)),
    CONSTRAINT payment_record_target_type_ck CHECK (target_type IN ('ORDER', 'PARTITION', 'CREDIT_ACCOUNT')),
    CONSTRAINT payment_record_local_hour_ck CHECK (local_hour BETWEEN 0 AND 23)
);

CREATE INDEX payment_record_store_day_ix ON reporting.payment_record (store_id, business_date);
CREATE INDEX payment_record_store_day_method_ix ON reporting.payment_record (store_id, business_date, method_code);

-- A posted credit sale (on account), in its order's business day.
CREATE TABLE reporting.credit_record
(
    charge_id     UUID                     NOT NULL,
    store_id      UUID                     NOT NULL,
    business_date DATE                     NOT NULL,
    customer_id   UUID                     NOT NULL,
    order_id      UUID,
    amount        NUMERIC(19, 4)           NOT NULL,
    projected_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT credit_record_pk PRIMARY KEY (charge_id),
    CONSTRAINT credit_record_amount_ck CHECK (amount > 0)
);

CREATE INDEX credit_record_store_day_ix ON reporting.credit_record (store_id, business_date);

-- A void of a confirmed line, in its order's business day; local_hour is the store's wall clock at the void.
CREATE TABLE reporting.void_record
(
    void_id        UUID                     NOT NULL,
    store_id       UUID                     NOT NULL,
    business_date  DATE                     NOT NULL,
    local_hour     SMALLINT                 NOT NULL,
    item_code      VARCHAR(50),
    description    VARCHAR(200)             NOT NULL,
    quantity       NUMERIC(12, 3)           NOT NULL,
    gross          NUMERIC(19, 4)           NOT NULL,
    reason         VARCHAR(200)             NOT NULL,
    wasted         BOOLEAN                  NOT NULL,
    after_dispatch BOOLEAN                  NOT NULL,
    employee_id    UUID                     NOT NULL,
    approver_id    UUID,
    projected_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT void_record_pk PRIMARY KEY (void_id),
    CONSTRAINT void_record_quantity_ck CHECK (quantity > 0),
    CONSTRAINT void_record_local_hour_ck CHECK (local_hour BETWEEN 0 AND 23)
);

CREATE INDEX void_record_store_day_ix ON reporting.void_record (store_id, business_date);

-- A rebuild of a store's projections over a business-date range (D84): one RUNNING run per store at a time.
CREATE TABLE reporting.rebuild_run
(
    id                     UUID                     NOT NULL,
    store_id               UUID                     NOT NULL,
    from_date              DATE                     NOT NULL,
    to_date                DATE                     NOT NULL,
    status                 VARCHAR(10)              NOT NULL,
    projected              INTEGER                  NOT NULL DEFAULT 0,
    started_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at            TIMESTAMP WITH TIME ZONE,
    error                  VARCHAR(500),
    CONSTRAINT rebuild_run_pk PRIMARY KEY (id),
    CONSTRAINT rebuild_run_status_ck CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT rebuild_run_range_ck CHECK (from_date <= to_date),
    CONSTRAINT rebuild_run_finished_ck CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT rebuild_run_projected_ck CHECK (projected >= 0)
);

CREATE UNIQUE INDEX rebuild_run_running_uk ON reporting.rebuild_run (store_id) WHERE status = 'RUNNING';
CREATE INDEX rebuild_run_store_started_ix ON reporting.rebuild_run (store_id, started_at);
