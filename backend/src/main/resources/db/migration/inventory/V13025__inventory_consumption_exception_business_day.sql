-- S53 review: the business day of the order an exception's round belongs to (BatchFacts.businessDay), so reporting
-- tells which day's cost is missing; a round confirmed after the cutoff still belongs to its order's day. Nullable:
-- rows written before this have none, and reporting then takes their confirmation instant conservatively.
ALTER TABLE inventory.consumption_exception
    ADD COLUMN business_day DATE;
CREATE INDEX consumption_exception_open_day_ix ON inventory.consumption_exception (store_id, business_day)
    WHERE resolved_at IS NULL;
