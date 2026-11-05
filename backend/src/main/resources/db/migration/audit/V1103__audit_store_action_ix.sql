-- D82: "all voids of this store this week" is the common audit query. CREATE INDEX writes no row, so the
-- append-only trigger is not involved.
CREATE INDEX audit_event_store_action_ix ON audit.audit_event (store_id, action, occurred_at);
