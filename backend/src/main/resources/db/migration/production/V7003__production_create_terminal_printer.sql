-- Where a terminal's subtotals and documents print (D56). Terminals are referenced by id only (D6).
CREATE TABLE production.terminal_printer
(
    terminal_id UUID NOT NULL,
    store_id    UUID NOT NULL,
    printer_id  UUID NOT NULL,
    CONSTRAINT terminal_printer_pk PRIMARY KEY (terminal_id),
    CONSTRAINT terminal_printer_printer_fk FOREIGN KEY (printer_id, store_id)
        REFERENCES production.printer (id, store_id)
);

CREATE INDEX terminal_printer_store_ix ON production.terminal_printer (store_id);
