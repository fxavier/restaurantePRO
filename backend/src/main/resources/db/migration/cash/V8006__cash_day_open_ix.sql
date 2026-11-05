-- The store's oldest OPEN cash day (GET /api/v1/pos/cash/days/current): few rows per store are OPEN, so a partial
-- index finds it without walking every day of the store's history.
CREATE INDEX cash_day_store_open_ix ON cash.cash_day (store_id, business_date) WHERE status = 'OPEN';
