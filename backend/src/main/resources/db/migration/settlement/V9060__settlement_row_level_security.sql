-- Row-level security on store_id for settlement (ADR 0039, D167, D168): 8 STORE tables, 4 CHILD tables through their
-- in-module parent, 2 UNSCOPED. Every policy comes from the V1008 helpers.
SELECT shared.rls_protect_store_table('settlement.payment_attempt');
SELECT shared.rls_protect_store_table('settlement.payment');
SELECT shared.rls_protect_store_table('settlement.refund');
SELECT shared.rls_protect_store_table('settlement.bill_partition');
SELECT shared.rls_protect_store_table('settlement.credit_charge');
SELECT shared.rls_protect_store_table('settlement.reconciliation_batch');
SELECT shared.rls_protect_store_table('settlement.reconciliation_entry');
SELECT shared.rls_protect_store_table('settlement.tax_rounding_adjustment');

SELECT shared.rls_protect_child_table('settlement.payment_allocation', 'payment_id', 'settlement.payment', 'id');
SELECT shared.rls_protect_child_table('settlement.bill_partition_line', 'partition_id', 'settlement.bill_partition',
                                      'id');
SELECT shared.rls_protect_child_table('settlement.reconciliation_resolution', 'entry_id',
                                      'settlement.reconciliation_entry', 'id');
SELECT shared.rls_protect_child_table('settlement.tax_correction_resolution', 'adjustment_id',
                                      'settlement.tax_rounding_adjustment', 'id');

SELECT shared.rls_register_unscoped('settlement.payment_method',
                                    'Company-wide configuration of the payment methods (D167)');
SELECT shared.rls_register_unscoped('settlement.order_settlement',
                                    'The D51 lock row: an order id and a timestamp, no business data; a parent lookup '
                                        || 'would cross into orders (D6, D167)');

-- Trigger functions run as the invoking role, so under a store scope the ceiling check reads only visible payments.
-- A refund (of this store, so WITH CHECK passes) pointing at another store's payment would read no payment, and
-- "refunded > NULL" is not true: the ceiling would silently vanish. Fail closed instead, as WITH CHECK would (42501
-- and "row-level security", so StoreScopeViolations counts it as a refused write). Unchanged for a visible payment.
CREATE OR REPLACE FUNCTION settlement.check_refund_within_payment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    paid     NUMERIC(19, 4);
    refunded NUMERIC(19, 4);
BEGIN
    SELECT p.amount INTO paid FROM settlement.payment p WHERE p.id = NEW.payment_id;
    IF paid IS NULL THEN
        RAISE EXCEPTION 'refund % refused by row-level security: payment % is outside the store scope', NEW.id,
            NEW.payment_id USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT coalesce(sum(r.amount), 0) INTO refunded FROM settlement.refund r WHERE r.payment_id = NEW.payment_id;
    IF refunded > paid THEN
        RAISE EXCEPTION 'payment % refunds % of %', NEW.payment_id, refunded, paid
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refund_within_payment';
    END IF;
    RETURN NULL;
END;
$$;
