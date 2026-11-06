-- P22, D79 (4): a manager may close a session its operator abandoned. The session records who closed it
-- (closed_by_employee_id, since V8003), whether the operator or a manager closed it, and the manager's reason.
-- Existing closed sessions were closed by their operator: the column default fills them without an UPDATE (a
-- closed session is guarded against change), then the still-open sessions are cleared and the default dropped.
ALTER TABLE cash.cash_session
    ADD COLUMN close_kind   VARCHAR(10) DEFAULT 'OPERATOR',
    ADD COLUMN close_reason TEXT;

UPDATE cash.cash_session SET close_kind = NULL WHERE status = 'OPEN';

ALTER TABLE cash.cash_session
    ALTER COLUMN close_kind DROP DEFAULT,
    ADD CONSTRAINT cash_session_close_kind_ck CHECK (
        (status = 'CLOSED') = (close_kind IS NOT NULL)
            AND coalesce(close_kind, 'OPERATOR') IN ('OPERATOR', 'FORCED')
            AND (coalesce(close_kind, '') = 'FORCED') = (close_reason IS NOT NULL)
            AND btrim(coalesce(close_reason, 'x')) <> '');
