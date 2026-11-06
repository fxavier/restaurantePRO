-- D86/D88: pending (not completed) publications per listener, for EventPublicationStats.pendingFor(prefix) and the
-- recovery job. Partial, so it stays small however many completed rows the registry keeps (30 days, D88).
-- text_pattern_ops lets the planner use it for "listener_id LIKE 'prefix%'" under a non-C collation.
CREATE INDEX event_publication_pending_ix ON event_publication (listener_id text_pattern_ops)
    WHERE completion_date IS NULL;
