-- Table lock indicator (spec §4, §8.4: a locked table accepts no new operations; payments and close stay allowed).
-- An independent flag, not a state of the table.
ALTER TABLE dining.restaurant_table
    ADD COLUMN locked_at             TIMESTAMP WITH TIME ZONE,
    ADD COLUMN locked_by_employee_id UUID,
    ADD COLUMN lock_reason           VARCHAR(200),
    -- locked exactly when it has a time, an employee and a reason
    ADD CONSTRAINT restaurant_table_lock_complete_ck CHECK (
        (locked_at IS NULL AND locked_by_employee_id IS NULL AND lock_reason IS NULL)
        OR (locked_at IS NOT NULL AND locked_by_employee_id IS NOT NULL AND btrim(lock_reason) <> ''));
