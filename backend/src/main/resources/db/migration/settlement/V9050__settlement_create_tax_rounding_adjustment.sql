-- The tax cent of a settled void (D96, PRD S34). A settled void that empties a split line's pool leaves that line's
-- recomputed tax and Σ its LIVE shares' tax apart by amount (signed, at most one minor unit). When the order's
-- residual document is drafted, the cent is placed on one residual line of the same rate (ABSORBED: target_line_id
-- is that line's order line, target_partition_id its share's partition, NULL for a pool line); when nothing can take
-- it, it is a PENDING_CORRECTION of document_id, the document a correction process must amend (an ISSUED document is
-- never changed). One row per drifting line, written once: a retried draft re-applies the row, never places again.
-- A fact: the database refuses UPDATE, DELETE and TRUNCATE. order_id, store_id, the lines, partitions, document and
-- actor refer to other modules or rows by id only (D6).
CREATE TABLE settlement.tax_rounding_adjustment
(
    id                  UUID                     NOT NULL,
    order_id            UUID                     NOT NULL,
    store_id            UUID                     NOT NULL,
    business_date       DATE                     NOT NULL,
    source_line_id      UUID                     NOT NULL,
    rate_percent        NUMERIC(7, 4)            NOT NULL,
    amount              NUMERIC(19, 4)           NOT NULL,
    currency            CHAR(3)                  NOT NULL,
    kind                VARCHAR(20)              NOT NULL,
    document_id         UUID                     NOT NULL,
    target_line_id      UUID,
    target_partition_id UUID,
    actor_kind          VARCHAR(10)              NOT NULL,
    actor_id            UUID,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT tax_rounding_adjustment_pk PRIMARY KEY (id),
    CONSTRAINT tax_rounding_adjustment_line_uk UNIQUE (order_id, source_line_id),
    CONSTRAINT tax_rounding_adjustment_amount_ck CHECK (amount <> 0),
    CONSTRAINT tax_rounding_adjustment_kind_ck CHECK (kind IN ('ABSORBED', 'PENDING_CORRECTION')),
    CONSTRAINT tax_rounding_adjustment_target_ck CHECK (kind <> 'ABSORBED' OR target_line_id IS NOT NULL),
    CONSTRAINT tax_rounding_adjustment_actor_ck CHECK (
        (actor_kind = 'SYSTEM' AND actor_id IS NULL)
            OR (actor_kind IN ('ACCOUNT', 'EMPLOYEE') AND actor_id IS NOT NULL))
);
CREATE INDEX tax_rounding_adjustment_order_ix ON settlement.tax_rounding_adjustment (order_id);
CREATE INDEX tax_rounding_adjustment_pending_ix ON settlement.tax_rounding_adjustment (store_id, created_at)
    WHERE kind = 'PENDING_CORRECTION';

CREATE TRIGGER tax_rounding_adjustment_append_only
    BEFORE UPDATE OR DELETE
    ON settlement.tax_rounding_adjustment
    FOR EACH ROW
EXECUTE FUNCTION settlement.reject_fact_change();
CREATE TRIGGER tax_rounding_adjustment_no_truncate
    BEFORE TRUNCATE
    ON settlement.tax_rounding_adjustment
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();
