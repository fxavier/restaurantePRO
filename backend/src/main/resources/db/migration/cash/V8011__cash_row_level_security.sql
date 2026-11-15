-- Row-level security on store_id for cash (ADR 0039, D167, D168): 6 STORE tables and 2 CHILD tables through their
-- in-module parent. Every policy comes from the V1008 helpers. V8010 stays reserved for R5's S61 (D172).
SELECT shared.rls_protect_store_table('cash.cash_register');
SELECT shared.rls_protect_store_table('cash.cash_day');
SELECT shared.rls_protect_store_table('cash.cash_day_reopening');
SELECT shared.rls_protect_store_table('cash.cash_session');
SELECT shared.rls_protect_store_table('cash.cash_movement');
SELECT shared.rls_protect_store_table('cash.cash_closing');

SELECT shared.rls_protect_child_table('cash.cash_session_count', 'session_id', 'cash.cash_session', 'id');
SELECT shared.rls_protect_child_table('cash.cash_closing_line', 'closing_id', 'cash.cash_closing', 'id');
