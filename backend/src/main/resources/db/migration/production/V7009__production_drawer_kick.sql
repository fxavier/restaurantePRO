-- R5 D158 (ADR 0038): a cash payment, a cash refund or the manual command opens the terminal's cash drawer through
-- its receipt printer, as a DRAWER_KICK ticket whose content format is KICK (its payload is ESC p from the printer's
-- profile, made at send time). Its key is (DRAWER_KICK, <payment, refund or manual command id>, 'DRAWER'): a
-- redelivered event kicks once. DDL only: tickets are append-only, no row is rewritten.
ALTER TABLE production.production_ticket
    DROP CONSTRAINT production_ticket_source_type_ck,
    ADD CONSTRAINT production_ticket_source_type_ck
        CHECK (source_type IN ('BATCH', 'VOID', 'SUBTOTAL', 'DOCUMENT', 'TEST', 'RECEIPT_SLIP', 'DELIVERY_SLIP',
                               'DRAWER_KICK')),
    ADD CONSTRAINT production_ticket_kick_format_ck CHECK ((source_type = 'DRAWER_KICK') = (content_format = 'KICK'));
