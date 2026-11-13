-- R5 D154-D157 (ADR 0038): a printer profile per printer, styled production tickets, the status probe's last answer.
-- Every existing printer gets the defaults, which print R4 tickets byte-identical (80 mm, 48 columns, partial cut,
-- feed 4, no probe, no drawer).
ALTER TABLE production.printer
    ADD COLUMN paper_width_mm  SMALLINT    NOT NULL DEFAULT 80,
    ADD COLUMN columns         SMALLINT    NOT NULL DEFAULT 48,
    ADD COLUMN code_table      SMALLINT,
    ADD COLUMN cut_mode        VARCHAR(8)  NOT NULL DEFAULT 'PARTIAL',
    ADD COLUMN feed_lines      SMALLINT    NOT NULL DEFAULT 4,
    ADD COLUMN status_probe    BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN drawer_pin      SMALLINT,
    ADD COLUMN drawer_pulse_ms SMALLINT    NOT NULL DEFAULT 100,
    -- what the last send learnt (D157): OK, PAPER_NEAR_END, COVER_OPEN, PAPER_OUT, PRINTER_ERROR, NOT_CONNECTED,
    -- INTERRUPTED; written by the print worker without moving the printer's version
    ADD COLUMN last_status     VARCHAR(20),
    ADD COLUMN last_status_at  TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT printer_paper_width_ck CHECK (paper_width_mm IN (58, 80)),
    ADD CONSTRAINT printer_columns_ck CHECK ((paper_width_mm = 58 AND columns BETWEEN 24 AND 35)
        OR (paper_width_mm = 80 AND columns BETWEEN 36 AND 64)),
    ADD CONSTRAINT printer_code_table_ck CHECK (code_table BETWEEN 0 AND 255),
    ADD CONSTRAINT printer_cut_mode_ck CHECK (cut_mode IN ('PARTIAL', 'FULL', 'NONE')),
    ADD CONSTRAINT printer_feed_lines_ck CHECK (feed_lines BETWEEN 0 AND 10),
    ADD CONSTRAINT printer_drawer_pin_ck CHECK (drawer_pin IN (0, 1)),
    ADD CONSTRAINT printer_drawer_pulse_ck CHECK (drawer_pulse_ms BETWEEN 2 AND 510 AND drawer_pulse_ms % 2 = 0),
    ADD CONSTRAINT printer_last_status_ck CHECK ((last_status IS NULL) = (last_status_at IS NULL));

-- D155: R4 tickets and fiscal printouts stay PLAIN text lines; production's own layouts are STYLED_V1 JSON lines.
-- KICK is the drawer kick's payload (D158, S60). Tickets are append-only: no row is rewritten.
ALTER TABLE production.production_ticket
    ADD COLUMN content_format VARCHAR(10) NOT NULL DEFAULT 'PLAIN',
    ADD CONSTRAINT production_ticket_content_format_ck CHECK (content_format IN ('PLAIN', 'STYLED_V1', 'KICK'));

-- routing an UNROUTED ticket still changes only routing and printer_id: content_format is a fact like content
CREATE OR REPLACE FUNCTION production.reject_ticket_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.routing = 'UNROUTED' AND NEW.routing = 'ROUTED' AND NEW.printer_id IS NOT NULL
            AND (NEW.id, NEW.store_id, NEW.source_type, NEW.source_id, NEW.destination, NEW.kind, NEW.order_id,
                 NEW.terminal_id, NEW.suppressed, NEW.content, NEW.content_format, NEW.qr_payload, NEW.created_at)
                IS NOT DISTINCT FROM
                (OLD.id, OLD.store_id, OLD.source_type, OLD.source_id, OLD.destination, OLD.kind, OLD.order_id,
                 OLD.terminal_id, OLD.suppressed, OLD.content, OLD.content_format, OLD.qr_payload, OLD.created_at) THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'production.production_ticket is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- D157: print_job.last_error also takes COVER_OPEN, PAPER_OUT and PRINTER_ERROR (no CHECK on it, none added).

-- D150 PRINT_JOBS_ATTENTION: the unresolved FAILED or UNKNOWN jobs of a store, newest window first
CREATE INDEX print_job_unresolved_ix ON production.print_job (store_id, created_at)
    WHERE status IN ('FAILED', 'UNKNOWN') AND resolution IS NULL;
