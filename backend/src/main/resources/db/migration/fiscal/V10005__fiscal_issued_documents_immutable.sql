-- Confirmed facts are never edited or deleted (spec §8, r2-decisions §4 fiscal, A15). An ISSUED document, its lines
-- and its tax summary are frozen by the database itself; corrections are new documents (market run / R3). A DRAFT
-- may still change (last_error, DRAFT -> ISSUED). Follows the audit trigger's pattern (D18, D61).
CREATE FUNCTION fiscal.reject_issued_document_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status = 'ISSUED' THEN
        RAISE EXCEPTION 'fiscal.fiscal_document %: an ISSUED document is immutable (% refused)', OLD.id, TG_OP
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER fiscal_document_issued_immutable
    BEFORE UPDATE OR DELETE
    ON fiscal.fiscal_document
    FOR EACH ROW
EXECUTE FUNCTION fiscal.reject_issued_document_change();

-- Lines and tax rows: no insert, update or delete once their document is ISSUED.
CREATE FUNCTION fiscal.reject_issued_document_part_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    parent UUID := CASE WHEN TG_OP = 'INSERT' THEN NEW.document_id ELSE OLD.document_id END;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION '%.%: a row never moves to another document', TG_TABLE_SCHEMA, TG_TABLE_NAME
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM fiscal.fiscal_document d WHERE d.id = parent AND d.status = 'ISSUED') THEN
        RAISE EXCEPTION '%.%: document % is ISSUED and immutable (% refused)', TG_TABLE_SCHEMA, TG_TABLE_NAME,
            parent, TG_OP USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER fiscal_document_line_issued_immutable
    BEFORE INSERT OR UPDATE OR DELETE
    ON fiscal.fiscal_document_line
    FOR EACH ROW
EXECUTE FUNCTION fiscal.reject_issued_document_part_change();

CREATE TRIGGER fiscal_document_tax_issued_immutable
    BEFORE INSERT OR UPDATE OR DELETE
    ON fiscal.fiscal_document_tax
    FOR EACH ROW
EXECUTE FUNCTION fiscal.reject_issued_document_part_change();

-- TRUNCATE skips row triggers, so it is refused outright on every document table.
CREATE FUNCTION fiscal.reject_document_truncate() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.%: TRUNCATE is not allowed on fiscal documents', TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER fiscal_document_no_truncate
    BEFORE TRUNCATE ON fiscal.fiscal_document
    FOR EACH STATEMENT EXECUTE FUNCTION fiscal.reject_document_truncate();
CREATE TRIGGER fiscal_document_line_no_truncate
    BEFORE TRUNCATE ON fiscal.fiscal_document_line
    FOR EACH STATEMENT EXECUTE FUNCTION fiscal.reject_document_truncate();
CREATE TRIGGER fiscal_document_tax_no_truncate
    BEFORE TRUNCATE ON fiscal.fiscal_document_tax
    FOR EACH STATEMENT EXECUTE FUNCTION fiscal.reject_document_truncate();
