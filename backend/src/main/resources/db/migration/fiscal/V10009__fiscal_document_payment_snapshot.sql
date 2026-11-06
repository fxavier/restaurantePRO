-- D97: the payment breakdown of a document, a snapshot settlement records on the DRAFT just before its issue, frozen
-- with it (V10005 refuses any change of an ISSUED header, and the same trigger guards the rows below). No row is
-- updated (D93): R2 and pre-D97 R3 documents keep NULL and print as before.
ALTER TABLE fiscal.fiscal_document
    ADD COLUMN payment_status    VARCHAR(20),
    ADD COLUMN paid_total        NUMERIC(19, 4),
    ADD COLUMN credit_total      NUMERIC(19, 4),
    ADD COLUMN outstanding_total NUMERIC(19, 4);

ALTER TABLE fiscal.fiscal_document
    ADD CONSTRAINT fiscal_document_payment_status_ck CHECK (payment_status IS NULL
        OR payment_status = 'UNPAID' OR payment_status = 'PARTIALLY_PAID' OR payment_status = 'PAID'
        OR payment_status = 'ON_ACCOUNT' OR payment_status = 'MIXED'),
    ADD CONSTRAINT fiscal_document_payment_amounts_ck CHECK (paid_total >= 0 AND credit_total >= 0
        AND outstanding_total >= 0),
    -- all or nothing, and the breakdown accounts for the whole gross
    ADD CONSTRAINT fiscal_document_payment_snapshot_ck CHECK (
        (payment_status IS NULL AND paid_total IS NULL AND credit_total IS NULL AND outstanding_total IS NULL)
        OR (payment_status IS NOT NULL AND paid_total IS NOT NULL AND credit_total IS NOT NULL
            AND outstanding_total IS NOT NULL AND paid_total + credit_total + outstanding_total = total_gross)),
    -- D97 rule 5: a document is on account exactly when it carries a credit share
    ADD CONSTRAINT fiscal_document_payment_on_account_ck CHECK (payment_status IS NULL
        OR on_account = (credit_total > 0)),
    -- D97 rule 3, the PaymentStatus.of table (a zero gross is PAID)
    ADD CONSTRAINT fiscal_document_payment_status_totals_ck CHECK (payment_status IS NULL
        OR (payment_status = 'UNPAID' AND paid_total = 0 AND credit_total = 0 AND outstanding_total > 0)
        OR (payment_status = 'PARTIALLY_PAID' AND credit_total = 0 AND paid_total > 0 AND outstanding_total > 0)
        OR (payment_status = 'PAID' AND credit_total = 0 AND outstanding_total = 0)
        OR (payment_status = 'ON_ACCOUNT' AND credit_total > 0 AND credit_total = total_gross)
        OR (payment_status = 'MIXED' AND credit_total > 0 AND credit_total < total_gross));

-- The paid methods of the snapshot, in print order. Σ amount = paid_total is kept by FiscalDocument.recordPayments.
CREATE TABLE fiscal.fiscal_document_payment
(
    document_id UUID           NOT NULL,
    position    SMALLINT       NOT NULL,
    method_code VARCHAR(40)    NOT NULL,
    label       VARCHAR(120)   NOT NULL,
    amount      NUMERIC(19, 4) NOT NULL,
    CONSTRAINT fiscal_document_payment_pk PRIMARY KEY (document_id, position),
    CONSTRAINT fiscal_document_payment_document_fk FOREIGN KEY (document_id) REFERENCES fiscal.fiscal_document (id),
    CONSTRAINT fiscal_document_payment_position_ck CHECK (position >= 1),
    CONSTRAINT fiscal_document_payment_method_code_ck CHECK (btrim(method_code) <> ''),
    CONSTRAINT fiscal_document_payment_label_ck CHECK (btrim(label) <> ''),
    CONSTRAINT fiscal_document_payment_amount_ck CHECK (amount > 0)
);

CREATE TRIGGER fiscal_document_payment_issued_immutable
    BEFORE INSERT OR UPDATE OR DELETE
    ON fiscal.fiscal_document_payment
    FOR EACH ROW
EXECUTE FUNCTION fiscal.reject_issued_document_part_change();

CREATE TRIGGER fiscal_document_payment_no_truncate
    BEFORE TRUNCATE ON fiscal.fiscal_document_payment
    FOR EACH STATEMENT EXECUTE FUNCTION fiscal.reject_document_truncate();
