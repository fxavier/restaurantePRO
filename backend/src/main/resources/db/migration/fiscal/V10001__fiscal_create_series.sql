-- Per-store numbering series (spec §8.3, r2-decisions §4 fiscal, D50 §7.6). No SEQUENCE: the number is taken
-- under this row's lock inside the issuing transaction, so a rollback leaves no gap. Stores are referenced by id
-- only (D6).
CREATE SCHEMA IF NOT EXISTS fiscal;

CREATE TABLE fiscal.fiscal_series
(
    id            UUID                     NOT NULL,
    store_id      UUID                     NOT NULL,
    document_type VARCHAR(20)              NOT NULL,
    code          VARCHAR(20)              NOT NULL,
    first_number  BIGINT                   NOT NULL,
    next_number   BIGINT                   NOT NULL,
    active        BOOLEAN                  NOT NULL,
    version       BIGINT                   NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fiscal_series_pk PRIMARY KEY (id),
    CONSTRAINT fiscal_series_code_uk UNIQUE (store_id, document_type, code),
    CONSTRAINT fiscal_series_document_type_ck CHECK (document_type IN ('SALE')),
    CONSTRAINT fiscal_series_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT fiscal_series_first_number_ck CHECK (first_number >= 1),
    CONSTRAINT fiscal_series_next_number_ck CHECK (next_number >= first_number)
);

-- At most one active series per store and document type: issuing picks it.
CREATE UNIQUE INDEX fiscal_series_active_uk ON fiscal.fiscal_series (store_id, document_type) WHERE active;

-- next_number has no API (spec §9): the identity of a series never changes, it is never deleted, and issuing is
-- the only writer of next_number, which it advances by exactly one. A skipped or reused number fails here.
CREATE FUNCTION fiscal.guard_series_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'fiscal.fiscal_series rows are never deleted' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.store_id <> OLD.store_id OR NEW.document_type <> OLD.document_type OR NEW.code <> OLD.code
        OR NEW.first_number <> OLD.first_number THEN
        RAISE EXCEPTION 'fiscal.fiscal_series: store, type, code and first number are immutable'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.next_number <> OLD.next_number AND NEW.next_number <> OLD.next_number + 1 THEN
        RAISE EXCEPTION 'fiscal.fiscal_series: next_number only advances by one (% -> %)',
            OLD.next_number, NEW.next_number USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER fiscal_series_guard
    BEFORE UPDATE OR DELETE
    ON fiscal.fiscal_series
    FOR EACH ROW
EXECUTE FUNCTION fiscal.guard_series_change();
