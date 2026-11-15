-- Row-level security on store_id (ADR 0039, D167, D168): every production table belongs to one store, so each is
-- protected by the V1008 STORE helper. A connection reads and writes only the rows of its store scope, and no row ever
-- moves to another store. The print worker runs in the PRINT_WORKER system scope (all stores, D166).
SELECT shared.rls_protect_store_table('production.printer');
SELECT shared.rls_protect_store_table('production.destination_route');
SELECT shared.rls_protect_store_table('production.terminal_printer');
SELECT shared.rls_protect_store_table('production.production_ticket');
SELECT shared.rls_protect_store_table('production.print_job');
