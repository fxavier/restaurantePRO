-- The logical ticket per source x destination (spec §4, r2-decisions §4 production): a fact, append-only.
-- UNIQUE(source_type, source_id, destination) is the dedupe key: a redelivered event never makes a second ticket.
-- destination is the production destination code for BATCH and VOID tickets; for the terminal-printed sources it
-- names the printout: ORIGINAL or COPY-<n> for a DOCUMENT, SUBTOTAL-<digest of its content> for a SUBTOTAL, and the
-- printer code for a TEST. suppressed = the printer was in IGNORE: no job exists, visibly (§8.6), not a failure.
CREATE TABLE production.production_ticket
(
    id          UUID                     NOT NULL,
    store_id    UUID                     NOT NULL,
    source_type VARCHAR(10)              NOT NULL,
    source_id   UUID                     NOT NULL,
    destination VARCHAR(40)              NOT NULL,
    kind        VARCHAR(10)              NOT NULL,
    order_id    UUID,
    terminal_id UUID,
    printer_id  UUID                     NOT NULL,
    suppressed  BOOLEAN                  NOT NULL,
    content     TEXT                     NOT NULL,
    qr_payload  TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT production_ticket_pk PRIMARY KEY (id),
    CONSTRAINT production_ticket_source_uk UNIQUE (source_type, source_id, destination),
    CONSTRAINT production_ticket_printer_fk FOREIGN KEY (printer_id, store_id)
        REFERENCES production.printer (id, store_id),
    CONSTRAINT production_ticket_source_type_ck
        CHECK (source_type IN ('BATCH', 'VOID', 'SUBTOTAL', 'DOCUMENT', 'TEST')),
    CONSTRAINT production_ticket_kind_ck CHECK (kind IN ('ORIGINAL', 'COPY')),
    CONSTRAINT production_ticket_copy_is_document_ck CHECK (kind = 'ORIGINAL' OR source_type = 'DOCUMENT')
);

CREATE INDEX production_ticket_store_created_ix ON production.production_ticket (store_id, created_at);

CREATE FUNCTION production.reject_ticket_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'production.production_ticket is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER production_ticket_append_only
    BEFORE UPDATE OR DELETE
    ON production.production_ticket
    FOR EACH ROW
EXECUTE FUNCTION production.reject_ticket_change();

CREATE TRIGGER production_ticket_no_truncate
    BEFORE TRUNCATE ON production.production_ticket
    FOR EACH STATEMENT EXECUTE FUNCTION production.reject_ticket_change();
