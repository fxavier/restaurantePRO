-- Routing of a production destination (the line snapshot's productionDestination: KITCHEN, BAR...) to a printer of
-- the same store (D56). A destination with no route is not printed (logged and counted).
CREATE TABLE production.destination_route
(
    store_id         UUID        NOT NULL,
    destination_code VARCHAR(40) NOT NULL,
    printer_id       UUID        NOT NULL,
    CONSTRAINT destination_route_pk PRIMARY KEY (store_id, destination_code),
    CONSTRAINT destination_route_printer_fk FOREIGN KEY (printer_id, store_id)
        REFERENCES production.printer (id, store_id),
    CONSTRAINT destination_route_code_not_blank_ck CHECK (btrim(destination_code) <> '')
);
