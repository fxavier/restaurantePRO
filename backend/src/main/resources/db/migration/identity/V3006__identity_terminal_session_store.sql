-- D45: deactivating a store ends the POS sessions on its terminals, so each POS session remembers its store.
-- store_id refers to organization.store: no cross-module FK (D6).
-- Rows from before R2 are dropped: their sessions hold the R1 Actor, which this release cannot read (D38), so
-- they are already logged out; the mapping only pointed at sessions nobody can use.
DELETE FROM identity.terminal_session;

ALTER TABLE identity.terminal_session ADD COLUMN store_id UUID NOT NULL;
CREATE INDEX terminal_session_store_ix ON identity.terminal_session (store_id);
