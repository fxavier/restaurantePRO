-- Row-level security on store_id for fiscal (ADR 0039, D167, D168): 2 STORE tables and 4 CHILD tables of the
-- document. Every policy comes from the V1008 helpers. The immutability guards (V10005, V10009) and the series guard
-- (V10001) stay: RLS only adds the row filter.
SELECT shared.rls_protect_store_table('fiscal.fiscal_series');
SELECT shared.rls_protect_store_table('fiscal.fiscal_document');

SELECT shared.rls_protect_child_table('fiscal.fiscal_document_line', 'document_id', 'fiscal.fiscal_document', 'id');
SELECT shared.rls_protect_child_table('fiscal.fiscal_document_tax', 'document_id', 'fiscal.fiscal_document', 'id');
SELECT shared.rls_protect_child_table('fiscal.fiscal_document_payment', 'document_id', 'fiscal.fiscal_document',
                                      'id');
SELECT shared.rls_protect_child_table('fiscal.fiscal_document_print', 'document_id', 'fiscal.fiscal_document', 'id');
