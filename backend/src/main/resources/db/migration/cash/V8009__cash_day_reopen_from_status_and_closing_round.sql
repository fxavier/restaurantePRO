-- D79 (3), P19 (R3 part): a FINANCIALLY_CLOSED day may reopen too. The reopening records the state it undid and,
-- for a financial reopen, the financial close it undid. Every R2 reopening was from OPERATIONALLY_CLOSED: the column
-- default fills those rows without an UPDATE (the table is append-only).
ALTER TABLE cash.cash_day_reopening
    ADD COLUMN from_status                          VARCHAR(25) NOT NULL DEFAULT 'OPERATIONALLY_CLOSED',
    ADD COLUMN previous_financially_closed_at       TIMESTAMP WITH TIME ZONE,
    ADD COLUMN previous_financially_closed_by_id    UUID,
    ADD COLUMN previous_financially_closed_by_kind  VARCHAR(10);

ALTER TABLE cash.cash_day_reopening
    ALTER COLUMN from_status DROP DEFAULT,
    ADD CONSTRAINT cash_day_reopening_from_status_ck CHECK (
        from_status IN ('OPERATIONALLY_CLOSED', 'FINANCIALLY_CLOSED')
            AND (from_status = 'FINANCIALLY_CLOSED') = (previous_financially_closed_at IS NOT NULL)
            AND (previous_financially_closed_at IS NULL) = (previous_financially_closed_by_id IS NULL)
            AND (previous_financially_closed_at IS NULL) = (previous_financially_closed_by_kind IS NULL)
            AND coalesce(previous_financially_closed_by_kind, 'ACCOUNT') IN ('ACCOUNT', 'EMPLOYEE'));

-- spec §8.3: OPEN -> OPERATIONALLY_CLOSED -> FINANCIALLY_CLOSED, and the reopens OPERATIONALLY_CLOSED -> OPEN (P19)
-- and FINANCIALLY_CLOSED -> OPEN (D79 (3)). A financially closed day changes only by that reopen; a day is never
-- deleted.
CREATE OR REPLACE FUNCTION cash.guard_cash_day_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'cash.cash_day rows are never deleted' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.store_id <> OLD.store_id OR NEW.business_date <> OLD.business_date THEN
        RAISE EXCEPTION 'cash.cash_day store and business date never change' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'OPEN' AND NEW.status = 'OPERATIONALLY_CLOSED')
            OR (OLD.status = 'OPERATIONALLY_CLOSED' AND NEW.status IN ('FINANCIALLY_CLOSED', 'OPEN'))
            OR (OLD.status = 'FINANCIALLY_CLOSED' AND NEW.status = 'OPEN')) THEN
        RAISE EXCEPTION 'cash.cash_day cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'FINANCIALLY_CLOSED' AND NEW.status = 'FINANCIALLY_CLOSED' THEN
        RAISE EXCEPTION 'cash.cash_day % is financially closed', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- §8.3 "conserva fechos anteriores": a closing belongs to the day's round, the number of financial reopens of the
-- day when it was drafted. An APPROVED closing of an earlier round is kept, and the next round has its own live
-- closing and its own attempts.
ALTER TABLE cash.cash_closing
    ADD COLUMN day_round INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT cash_closing_day_round_ck CHECK (day_round >= 0);

DROP INDEX cash.cash_closing_live_uk;
CREATE UNIQUE INDEX cash_closing_live_uk ON cash.cash_closing (register_id, cash_day_id, day_round)
    WHERE status IN ('DRAFT', 'SUBMITTED', 'APPROVED');

ALTER TABLE cash.cash_closing
    DROP CONSTRAINT cash_closing_attempt_uk,
    ADD CONSTRAINT cash_closing_attempt_uk UNIQUE (register_id, cash_day_id, day_round, attempt_no);
