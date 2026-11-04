-- r2-decisions §12: a PIN set by a manager (reset-pin) is known to that manager. Until the employee sets their own
-- PIN, their POS sessions hold no authority and they cannot approve (D28).
ALTER TABLE identity.employee ADD COLUMN must_change_pin BOOLEAN NOT NULL DEFAULT FALSE;
