-- D82: the installation-wide list, count and export filter on the time range only and sort by (occurred_at, id).
CREATE INDEX audit_event_occurred_ix ON audit.audit_event (occurred_at, id);
