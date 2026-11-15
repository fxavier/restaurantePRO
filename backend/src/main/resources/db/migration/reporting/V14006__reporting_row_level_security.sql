-- Row-level security (ADR 0039, D167, D168). Every projection row and every rebuild run belongs to one store. The
-- listeners project under their event's store scope (D166), a rebuild under the requesting account's scope, and the
-- reports read under the actor's stores. Projections insert with ON CONFLICT DO NOTHING, which needs only WITH CHECK.
SELECT shared.rls_protect_store_table('reporting.sales_record');
SELECT shared.rls_protect_store_table('reporting.sales_record_line');
SELECT shared.rls_protect_store_table('reporting.sales_record_tax');
SELECT shared.rls_protect_store_table('reporting.payment_record');
SELECT shared.rls_protect_store_table('reporting.credit_record');
SELECT shared.rls_protect_store_table('reporting.void_record');
SELECT shared.rls_protect_store_table('reporting.discount_record');
SELECT shared.rls_protect_store_table('reporting.offer_cost_record');
SELECT shared.rls_protect_store_table('reporting.consumption_record');
SELECT shared.rls_protect_store_table('reporting.consumption_waste_record');
SELECT shared.rls_protect_store_table('reporting.cost_variance_record');
SELECT shared.rls_protect_store_table('reporting.rebuild_run');
