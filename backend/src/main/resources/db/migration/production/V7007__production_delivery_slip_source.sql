-- D129: DELIVERY_SLIP is the source of a delivery's non-fiscal slip, printed on the dispatching terminal's printer.
-- Its key is (DELIVERY_SLIP, <delivery event id>, 'ORIGINAL'): a redelivered event prints nothing more, a re-dispatch
-- (a new delivery event) prints a new slip.
ALTER TABLE production.production_ticket
    DROP CONSTRAINT production_ticket_source_type_ck,
    ADD CONSTRAINT production_ticket_source_type_ck
        CHECK (source_type IN ('BATCH', 'VOID', 'SUBTOTAL', 'DOCUMENT', 'TEST', 'RECEIPT_SLIP', 'DELIVERY_SLIP'));
