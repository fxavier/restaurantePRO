-- Row-level security (ADR 0039, D167): customers and credit are company-wide (P38); a ledger entry's store_id is
-- where it originated, not its owner, so these tables are UNSCOPED.
SELECT shared.rls_register_unscoped('customers.customer', 'Company-wide customers (P38)');
SELECT shared.rls_register_unscoped('customers.customer_phone', 'Company-wide customers (P38)');
SELECT shared.rls_register_unscoped('customers.customer_address', 'Company-wide customers (P38)');
SELECT shared.rls_register_unscoped('customers.credit_account', 'Company-wide credit (P38)');
SELECT shared.rls_register_unscoped('customers.credit_reservation', 'Company-wide credit (P38)');
SELECT shared.rls_register_unscoped('customers.credit_limit_change', 'Company-wide credit (P38)');
SELECT shared.rls_register_unscoped('customers.credit_settlement', 'Company-wide credit (P38)');
SELECT shared.rls_register_unscoped('customers.credit_ledger_entry',
                                    'Company-wide credit ledger (P38); its store_id is the origin of an entry');
