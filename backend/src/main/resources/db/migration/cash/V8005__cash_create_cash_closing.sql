-- Cash closings per register and cash day (spec §8.3, §8.4) and their lines per payment method.
CREATE TABLE cash.cash_closing
(
    id                       UUID                     NOT NULL,
    store_id                 UUID                     NOT NULL,
    register_id              UUID                     NOT NULL,
    cash_day_id              UUID                     NOT NULL,
    attempt_no               INTEGER                  NOT NULL,
    supersedes_id            UUID,
    status                   VARCHAR(10)              NOT NULL,
    version                  BIGINT                   NOT NULL,
    currency                 CHAR(3)                  NOT NULL,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by_employee_id   UUID                     NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at             TIMESTAMP WITH TIME ZONE,
    submitted_by_employee_id UUID,
    decided_at               TIMESTAMP WITH TIME ZONE,
    decided_by_id            UUID,
    decided_by_kind          VARCHAR(10),
    rejection_reason         TEXT,
    CONSTRAINT cash_closing_pk PRIMARY KEY (id),
    CONSTRAINT cash_closing_register_fk FOREIGN KEY (register_id, store_id)
        REFERENCES cash.cash_register (id, store_id),
    CONSTRAINT cash_closing_day_fk FOREIGN KEY (cash_day_id, store_id) REFERENCES cash.cash_day (id, store_id),
    CONSTRAINT cash_closing_supersedes_fk FOREIGN KEY (supersedes_id) REFERENCES cash.cash_closing (id),
    CONSTRAINT cash_closing_attempt_uk UNIQUE (register_id, cash_day_id, attempt_no),
    CONSTRAINT cash_closing_status_ck CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT cash_closing_attempt_ck CHECK (attempt_no >= 1 AND (attempt_no = 1) = (supersedes_id IS NULL)),
    CONSTRAINT cash_closing_submitted_ck CHECK (
        (status = 'DRAFT') = (submitted_at IS NULL)
            AND (submitted_at IS NULL) = (submitted_by_employee_id IS NULL)),
    CONSTRAINT cash_closing_decided_ck CHECK (
        (status IN ('APPROVED', 'REJECTED')) = (decided_at IS NOT NULL)
            AND (decided_at IS NULL) = (decided_by_id IS NULL)
            AND (decided_at IS NULL) = (decided_by_kind IS NULL)
            AND coalesce(decided_by_kind, 'ACCOUNT') IN ('ACCOUNT', 'EMPLOYEE')),
    CONSTRAINT cash_closing_rejection_ck CHECK (
        (status = 'REJECTED') = (rejection_reason IS NOT NULL) AND btrim(coalesce(rejection_reason, 'x')) <> '')
);
-- One live closing per register and day; a REJECTED one is kept and superseded by the next attempt.
CREATE UNIQUE INDEX cash_closing_live_uk ON cash.cash_closing (register_id, cash_day_id)
    WHERE status IN ('DRAFT', 'SUBMITTED', 'APPROVED');
CREATE INDEX cash_closing_day_ix ON cash.cash_closing (cash_day_id, status);

CREATE TABLE cash.cash_closing_line
(
    id                UUID           NOT NULL,
    closing_id        UUID           NOT NULL,
    method_code       VARCHAR(30)    NOT NULL,
    expected_amount   NUMERIC(19, 4) NOT NULL,
    counted_amount    NUMERIC(19, 4) NOT NULL,
    difference_amount NUMERIC(19, 4) NOT NULL,
    currency          CHAR(3)        NOT NULL,
    justification     TEXT,
    CONSTRAINT cash_closing_line_pk PRIMARY KEY (id),
    CONSTRAINT cash_closing_line_closing_fk FOREIGN KEY (closing_id) REFERENCES cash.cash_closing (id),
    CONSTRAINT cash_closing_line_method_uk UNIQUE (closing_id, method_code),
    CONSTRAINT cash_closing_line_counted_ck CHECK (counted_amount >= 0),
    CONSTRAINT cash_closing_line_difference_ck CHECK (difference_amount = counted_amount - expected_amount),
    CONSTRAINT cash_closing_line_justification_ck CHECK (justification IS NULL OR btrim(justification) <> '')
);

-- spec §8.3: a decided closing is preserved as it was; a submission needs every difference justified; nothing is
-- deleted.
CREATE FUNCTION cash.guard_cash_closing_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'cash.cash_closing % is %: % is not allowed', OLD.id, OLD.status, TG_OP
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED')
            OR (OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED', 'REJECTED'))) THEN
        RAISE EXCEPTION 'cash.cash_closing cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED' AND EXISTS (
        SELECT 1 FROM cash.cash_closing_line
        WHERE closing_id = NEW.id AND difference_amount <> 0 AND justification IS NULL) THEN
        RAISE EXCEPTION 'cash.cash_closing %: every difference needs a justification', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cash_closing_guarded
    BEFORE UPDATE OR DELETE
    ON cash.cash_closing
    FOR EACH ROW
EXECUTE FUNCTION cash.guard_cash_closing_change();

-- A line changes only its justification, and only while its closing is a DRAFT.
CREATE FUNCTION cash.guard_cash_closing_line_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE'
        OR (SELECT status FROM cash.cash_closing WHERE id = OLD.closing_id) <> 'DRAFT'
        OR (NEW.closing_id, NEW.method_code, NEW.expected_amount, NEW.counted_amount, NEW.difference_amount,
            NEW.currency) IS DISTINCT FROM
           (OLD.closing_id, OLD.method_code, OLD.expected_amount, OLD.counted_amount, OLD.difference_amount,
            OLD.currency) THEN
        RAISE EXCEPTION 'cash.cash_closing_line %: % is not allowed', OLD.id, TG_OP
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cash_closing_line_guarded
    BEFORE UPDATE OR DELETE
    ON cash.cash_closing_line
    FOR EACH ROW
EXECUTE FUNCTION cash.guard_cash_closing_line_change();
