-- Reprints of issued documents (spec §8.4: a reprint is an audited copy, never a new document, payment or movement).
-- copy_no 1, 2, ... per document; the unique key backs the next-copy rule under concurrency. Append-only.
CREATE TABLE fiscal.fiscal_document_print
(
    id                      UUID                     NOT NULL,
    document_id             UUID                     NOT NULL,
    copy_no                 INTEGER                  NOT NULL,
    requested_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_by_employee_id UUID                    NOT NULL,
    terminal_id             UUID                     NOT NULL,
    CONSTRAINT fiscal_document_print_pk PRIMARY KEY (id),
    CONSTRAINT fiscal_document_print_document_fk FOREIGN KEY (document_id) REFERENCES fiscal.fiscal_document (id),
    CONSTRAINT fiscal_document_print_copy_uk UNIQUE (document_id, copy_no),
    CONSTRAINT fiscal_document_print_copy_no_ck CHECK (copy_no >= 1)
);

CREATE FUNCTION fiscal.reject_print_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'fiscal.fiscal_document_print is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER fiscal_document_print_append_only
    BEFORE UPDATE OR DELETE
    ON fiscal.fiscal_document_print
    FOR EACH ROW
EXECUTE FUNCTION fiscal.reject_print_change();

CREATE TRIGGER fiscal_document_print_no_truncate
    BEFORE TRUNCATE ON fiscal.fiscal_document_print
    FOR EACH STATEMENT EXECUTE FUNCTION fiscal.reject_print_change();
