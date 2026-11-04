-- Which Spring Session belongs to which terminal, so revoking a terminal can end its POS sessions
-- (Spring Session JDBC indexes sessions by principal name only). session_id = shared.spring_session.session_id;
-- no FK: that table is Spring Session's, and a row here may outlive an expired session harmlessly.
CREATE TABLE identity.terminal_session
(
    session_id  VARCHAR(36)              NOT NULL,
    terminal_id UUID                     NOT NULL,
    employee_id UUID                     NOT NULL,
    started_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT terminal_session_pk PRIMARY KEY (session_id)
);
CREATE INDEX terminal_session_terminal_ix ON identity.terminal_session (terminal_id);
