-- D89 (1): work whose destination has no route, or whose terminal has no printer, is kept as an UNROUTED ticket with
-- no printer and no job, listed and routable later. Routing it is the one change a ticket ever takes: UNROUTED ->
-- ROUTED with its printer set, once (a compare-and-set on routing); every other column stays a fact.
-- D89 (2), no schema change: a SUBTOTAL ticket's key is now (SUBTOTAL, <request id>, 'SUBTOTAL'); one from a
-- publication stored by R2 (no request id) keeps the V7004 key (SUBTOTAL, <order id>, 'SUBTOTAL-<digest>').
-- D75: RECEIPT_SLIP is the source of a non-fiscal debt payment slip (its listener arrives with S29).
ALTER TABLE production.production_ticket
    ADD COLUMN routing VARCHAR(10) NOT NULL DEFAULT 'ROUTED',
    ALTER COLUMN printer_id DROP NOT NULL,
    ALTER COLUMN source_type TYPE VARCHAR(20),
    ADD CONSTRAINT production_ticket_routing_ck CHECK (routing IN ('ROUTED', 'UNROUTED')),
    ADD CONSTRAINT production_ticket_unrouted_no_printer_ck CHECK ((routing = 'UNROUTED') = (printer_id IS NULL)),
    ADD CONSTRAINT production_ticket_unrouted_not_suppressed_ck CHECK (routing = 'ROUTED' OR NOT suppressed),
    DROP CONSTRAINT production_ticket_source_type_ck,
    ADD CONSTRAINT production_ticket_source_type_ck
        CHECK (source_type IN ('BATCH', 'VOID', 'SUBTOTAL', 'DOCUMENT', 'TEST', 'RECEIPT_SLIP'));

-- the unrouted work of a store, oldest first
CREATE INDEX production_ticket_unrouted_ix ON production.production_ticket (store_id, created_at)
    WHERE routing = 'UNROUTED';

-- append-only, except routing an UNROUTED ticket: only routing and printer_id change, and only that way
CREATE OR REPLACE FUNCTION production.reject_ticket_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.routing = 'UNROUTED' AND NEW.routing = 'ROUTED' AND NEW.printer_id IS NOT NULL
            AND (NEW.id, NEW.store_id, NEW.source_type, NEW.source_id, NEW.destination, NEW.kind, NEW.order_id,
                 NEW.terminal_id, NEW.suppressed, NEW.content, NEW.qr_payload, NEW.created_at)
                IS NOT DISTINCT FROM
                (OLD.id, OLD.store_id, OLD.source_type, OLD.source_id, OLD.destination, OLD.kind, OLD.order_id,
                 OLD.terminal_id, OLD.suppressed, OLD.content, OLD.qr_payload, OLD.created_at) THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'production.production_ticket is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;
