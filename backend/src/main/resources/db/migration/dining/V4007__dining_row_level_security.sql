-- Row-level security on store_id (ADR 0039, D167, D168): every dining table belongs to one store, so each is
-- protected by the V1008 STORE helper. A connection reads and writes only the rows of its store scope, and no row ever
-- moves to another store. dining_settings is keyed by store_id itself.
SELECT shared.rls_protect_store_table('dining.room_area');
SELECT shared.rls_protect_store_table('dining.restaurant_table');
SELECT shared.rls_protect_store_table('dining.table_occupation');
SELECT shared.rls_protect_store_table('dining.reservation');
SELECT shared.rls_protect_store_table('dining.reservation_table');
SELECT shared.rls_protect_store_table('dining.dining_settings');
