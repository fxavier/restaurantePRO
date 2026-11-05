-- Provider statements against payments (D78, PRD S31). A batch is one imported statement of a store and a payment
-- method over a period: from the canonical CSV (source CSV) or fetched from the provider (source PROVIDER). Its
-- entries are what matching found, one per statement line plus one per payment the statement lacks; a resolution is
-- the decision about an entry (AUTO_MATCHED by the system for a MATCHED entry). All three are facts: the database
-- refuses UPDATE, DELETE and TRUNCATE. Reconciliation never writes a payment, a refund or a cash movement: it records
-- differences, and corrections go through their own commands (§8.4).
-- store_id, payment_id and the actors refer to other modules (or to settlement rows) by id only (D6).
-- file_sha256 is the statement's identity: SHA-256 of "store:<id>\nperiod:<from>..<to>\nmethod:<code>\n" followed by
-- the file's bytes (CSV) or the provider lines' canonical CSV (PROVIDER), so the same bytes for another store or
-- period are another statement.
CREATE TABLE settlement.reconciliation_batch
(
    id                     UUID                     NOT NULL,
    store_id               UUID                     NOT NULL,
    method_code            VARCHAR(30)              NOT NULL,
    period_from            DATE                     NOT NULL,
    period_to              DATE                     NOT NULL,
    currency               CHAR(3)                  NOT NULL,
    file_sha256            CHAR(64)                 NOT NULL,
    source                 VARCHAR(10)              NOT NULL,
    line_count             INTEGER                  NOT NULL,
    imported_by_account_id UUID                     NOT NULL,
    imported_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT reconciliation_batch_pk PRIMARY KEY (id),
    -- the same statement is imported once
    CONSTRAINT reconciliation_batch_file_uk UNIQUE (method_code, file_sha256),
    CONSTRAINT reconciliation_batch_source_ck CHECK (source IN ('CSV', 'PROVIDER')),
    CONSTRAINT reconciliation_batch_period_ck CHECK (period_from <= period_to),
    CONSTRAINT reconciliation_batch_line_count_ck CHECK (line_count >= 0),
    CONSTRAINT reconciliation_batch_sha_ck CHECK (file_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX reconciliation_batch_store_ix ON settlement.reconciliation_batch (store_id, imported_at);

-- line_no is the statement's data line (from 1); a MISSING_PROVIDER entry is about a payment, not a line, and has
-- none. difference = provider − internal, an absent side counting as zero.
CREATE TABLE settlement.reconciliation_entry
(
    id                 UUID                     NOT NULL,
    batch_id           UUID                     NOT NULL,
    line_no            INTEGER,
    store_id           UUID                     NOT NULL,
    kind               VARCHAR(20)              NOT NULL,
    payment_id         UUID,
    provider_reference VARCHAR(100),
    authorization_code VARCHAR(50),
    line_ref           VARCHAR(100),
    occurred_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    internal_amount    NUMERIC(19, 4),
    provider_amount    NUMERIC(19, 4),
    fee_amount         NUMERIC(19, 4),
    difference         NUMERIC(19, 4) GENERATED ALWAYS AS
                           (coalesce(provider_amount, 0) - coalesce(internal_amount, 0)) STORED,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT reconciliation_entry_pk PRIMARY KEY (id),
    CONSTRAINT reconciliation_entry_batch_fk FOREIGN KEY (batch_id) REFERENCES settlement.reconciliation_batch (id),
    CONSTRAINT reconciliation_entry_payment_fk FOREIGN KEY (payment_id) REFERENCES settlement.payment (id),
    CONSTRAINT reconciliation_entry_line_uk UNIQUE (batch_id, line_no),
    CONSTRAINT reconciliation_entry_kind_ck CHECK (kind IN ('MATCHED', 'AMOUNT_MISMATCH', 'MISSING_INTERNAL',
                                                            'MISSING_PROVIDER', 'DUPLICATE_LINE')),
    CONSTRAINT reconciliation_entry_line_ck CHECK ((line_no IS NULL) = (kind = 'MISSING_PROVIDER')
        AND (line_no IS NULL OR line_no > 0)),
    -- only a line the provider has carries its amount; only a payment we count carries ours
    CONSTRAINT reconciliation_entry_shape_ck CHECK (
        (kind = 'MATCHED' AND payment_id IS NOT NULL AND provider_amount = internal_amount)
            OR (kind = 'AMOUNT_MISMATCH' AND payment_id IS NOT NULL AND provider_amount <> internal_amount)
            OR (kind = 'MISSING_INTERNAL' AND payment_id IS NULL AND provider_amount IS NOT NULL
            AND internal_amount IS NULL)
            OR (kind = 'MISSING_PROVIDER' AND payment_id IS NOT NULL AND provider_amount IS NULL
            AND internal_amount IS NOT NULL AND fee_amount IS NULL)
            OR (kind = 'DUPLICATE_LINE' AND payment_id IS NOT NULL AND provider_amount IS NOT NULL
            AND internal_amount IS NULL)),
    CONSTRAINT reconciliation_entry_amounts_ck CHECK (provider_amount > 0 AND fee_amount >= 0
        AND internal_amount >= 0)
);
-- a payment is matched (or found with another amount) at most once, across every batch
CREATE UNIQUE INDEX reconciliation_entry_payment_uk ON settlement.reconciliation_entry (payment_id)
    WHERE kind IN ('MATCHED', 'AMOUNT_MISMATCH');
CREATE INDEX reconciliation_entry_store_kind_ix ON settlement.reconciliation_entry (store_id, kind);
CREATE INDEX reconciliation_entry_batch_ix ON settlement.reconciliation_entry (batch_id, kind);

-- One decision per entry. AUTO_MATCHED is the system's, for a MATCHED entry at import; the others are a person's
-- (RECONCILIATION_MANAGE), with a reason.
CREATE TABLE settlement.reconciliation_resolution
(
    id         UUID                     NOT NULL,
    entry_id   UUID                     NOT NULL,
    action     VARCHAR(25)              NOT NULL,
    reason     VARCHAR(500),
    actor_kind VARCHAR(10)              NOT NULL,
    actor_id   UUID,
    at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT reconciliation_resolution_pk PRIMARY KEY (id),
    CONSTRAINT reconciliation_resolution_entry_fk FOREIGN KEY (entry_id)
        REFERENCES settlement.reconciliation_entry (id),
    CONSTRAINT reconciliation_resolution_entry_uk UNIQUE (entry_id),
    CONSTRAINT reconciliation_resolution_action_ck CHECK (action IN ('ACCEPTED', 'REFUNDED_AT_PROVIDER', 'ESCALATED',
                                                                     'AUTO_MATCHED')),
    CONSTRAINT reconciliation_resolution_actor_ck CHECK (
        (action = 'AUTO_MATCHED' AND actor_kind = 'SYSTEM' AND actor_id IS NULL)
            OR (action <> 'AUTO_MATCHED' AND actor_kind IN ('ACCOUNT', 'EMPLOYEE') AND actor_id IS NOT NULL
            AND reason IS NOT NULL AND btrim(reason) <> ''))
);

CREATE TRIGGER reconciliation_batch_append_only
    BEFORE UPDATE OR DELETE
    ON settlement.reconciliation_batch
    FOR EACH ROW
EXECUTE FUNCTION settlement.reject_fact_change();
CREATE TRIGGER reconciliation_batch_no_truncate
    BEFORE TRUNCATE
    ON settlement.reconciliation_batch
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();

CREATE TRIGGER reconciliation_entry_append_only
    BEFORE UPDATE OR DELETE
    ON settlement.reconciliation_entry
    FOR EACH ROW
EXECUTE FUNCTION settlement.reject_fact_change();
CREATE TRIGGER reconciliation_entry_no_truncate
    BEFORE TRUNCATE
    ON settlement.reconciliation_entry
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();

CREATE TRIGGER reconciliation_resolution_append_only
    BEFORE UPDATE OR DELETE
    ON settlement.reconciliation_resolution
    FOR EACH ROW
EXECUTE FUNCTION settlement.reject_fact_change();
CREATE TRIGGER reconciliation_resolution_no_truncate
    BEFORE TRUNCATE
    ON settlement.reconciliation_resolution
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();
