-- A payment attempt (spec §7.3-§7.5, §8.3, D48, D50). order_id, store_id, terminal_id, session_id and the employees
-- refer to other modules by id only (D6). A timeout is UNKNOWN, never FAILED.
CREATE TABLE settlement.payment_attempt
(
    id                       UUID                     NOT NULL,
    order_id                 UUID                     NOT NULL,
    store_id                 UUID                     NOT NULL,
    terminal_id              UUID                     NOT NULL,
    session_id               UUID                     NOT NULL,
    method_code              VARCHAR(30)              NOT NULL,
    method_kind              VARCHAR(20)              NOT NULL,
    provider_code            VARCHAR(50),
    provider_reference       VARCHAR(100),
    provider_transaction_ref VARCHAR(100),
    amount                   NUMERIC(19, 4)           NOT NULL,
    tendered                 NUMERIC(19, 4),
    change_given             NUMERIC(19, 4),
    currency                 CHAR(3)                  NOT NULL,
    status                   VARCHAR(10)              NOT NULL,
    authorization_code       VARCHAR(50),
    outcome_detail           VARCHAR(500),
    created_by_employee_id   UUID                     NOT NULL,
    resolved_by_employee_id  UUID,
    approver_employee_id     UUID,
    resolution_reason        VARCHAR(500),
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    version                  BIGINT                   NOT NULL,
    CONSTRAINT payment_attempt_pk PRIMARY KEY (id),
    CONSTRAINT payment_attempt_status_ck CHECK (status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED',
                                                           'UNKNOWN')),
    CONSTRAINT payment_attempt_kind_ck CHECK (method_kind IN ('CASH', 'EXTERNAL_TERMINAL', 'INTEGRATED')),
    CONSTRAINT payment_attempt_amount_ck CHECK (amount > 0),
    -- Cash: tendered >= amount and change = tendered - amount. Change is for cash only (P12).
    CONSTRAINT payment_attempt_cash_ck CHECK (
        (method_kind = 'CASH' AND tendered IS NOT NULL AND tendered >= amount AND change_given = tendered - amount)
            OR (method_kind <> 'CASH' AND tendered IS NULL AND change_given IS NULL)),
    -- An integrated attempt is persisted with its provider and reference before the provider is called (§7.3).
    CONSTRAINT payment_attempt_provider_ck CHECK (
        (method_kind = 'INTEGRATED') = (provider_code IS NOT NULL AND provider_reference IS NOT NULL)),
    -- Only an integrated attempt is ever UNKNOWN; cash never waits (CREATED -> SUCCEEDED in one transaction).
    CONSTRAINT payment_attempt_unknown_ck CHECK (status <> 'UNKNOWN' OR method_kind = 'INTEGRATED'),
    CONSTRAINT payment_attempt_reclassified_ck CHECK (
        approver_employee_id IS NULL OR btrim(coalesce(resolution_reason, '')) <> ''),
    CONSTRAINT payment_attempt_provider_reference_uk UNIQUE (provider_code, provider_reference)
);
CREATE INDEX payment_attempt_order_ix ON settlement.payment_attempt (order_id);
CREATE INDEX payment_attempt_session_status_ix ON settlement.payment_attempt (session_id, status);
-- The sweeper and the in-doubt reads (D48, D50 §7.4).
CREATE INDEX payment_attempt_open_ix ON settlement.payment_attempt (status, updated_at)
    WHERE status IN ('CREATED', 'PENDING', 'UNKNOWN');
-- While an attempt of the order is CREATED, PENDING or UNKNOWN, no second one is created (§7.4: 409 PAYMENT_IN_DOUBT).
CREATE UNIQUE INDEX payment_attempt_open_per_order_uk ON settlement.payment_attempt (order_id)
    WHERE status IN ('CREATED', 'PENDING', 'UNKNOWN');
