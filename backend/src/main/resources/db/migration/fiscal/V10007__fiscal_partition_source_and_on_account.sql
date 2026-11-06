-- R3 (D70, D85, r3-decisions §fiscal): a document may also be issued for an ITEMS bill partition, may be a credit
-- sale ("a crédito") of a customer, and carries its order's business day. No row is updated (D93): the new columns
-- are nullable or have a DEFAULT, and order_ref is a stored generated column, so R2's ISSUED rows get it without
-- firing the immutability trigger (V10005), which stays untouched.
ALTER TABLE fiscal.fiscal_document DROP CONSTRAINT fiscal_document_source_type_ck;
ALTER TABLE fiscal.fiscal_document
    ADD CONSTRAINT fiscal_document_source_type_ck CHECK (source_type IN ('ORDER', 'PARTITION'));

ALTER TABLE fiscal.fiscal_document
    ADD COLUMN order_id      UUID,
    ADD COLUMN on_account    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN customer_id   UUID,
    ADD COLUMN business_date DATE;

-- The order a document belongs to, whatever its source: what coverage(orderId) and the order filter read.
ALTER TABLE fiscal.fiscal_document
    ADD COLUMN order_ref UUID GENERATED ALWAYS AS
        (coalesce(order_id, CASE WHEN source_type = 'ORDER' THEN source_id END)) STORED;

ALTER TABLE fiscal.fiscal_document
    ADD CONSTRAINT fiscal_document_partition_order_ck CHECK (source_type <> 'PARTITION' OR order_id IS NOT NULL),
    -- an ORDER document's order is its source
    ADD CONSTRAINT fiscal_document_order_source_ck CHECK (source_type <> 'ORDER' OR order_id IS NULL
        OR order_id = source_id),
    ADD CONSTRAINT fiscal_document_on_account_customer_ck CHECK (NOT on_account OR customer_id IS NOT NULL);

CREATE INDEX fiscal_document_order_ref_ix ON fiscal.fiscal_document (order_ref);
CREATE INDEX fiscal_document_customer_ix ON fiscal.fiscal_document (customer_id) WHERE customer_id IS NOT NULL;
-- issuedDocumentIds(storeId, from, to): the reporting backfill by business day
CREATE INDEX fiscal_document_store_business_date_ix ON fiscal.fiscal_document (store_id, business_date);
