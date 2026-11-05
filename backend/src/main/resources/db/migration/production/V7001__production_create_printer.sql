-- Network printers of a store (spec §4 "Produção e impressão", §8.6; r2-decisions §4 production, D56, D57). Never
-- deleted (tickets and jobs refer to them): deactivated instead. Stores are referenced by id only (D6).
CREATE SCHEMA IF NOT EXISTS production;

CREATE TABLE production.printer
(
    id                  UUID                     NOT NULL,
    store_id            UUID                     NOT NULL,
    code                VARCHAR(20)              NOT NULL,
    name                VARCHAR(120)             NOT NULL,
    host                VARCHAR(255)             NOT NULL,
    port                INTEGER                  NOT NULL,
    code_page           VARCHAR(10)              NOT NULL,
    active              BOOLEAN                  NOT NULL,
    mode                VARCHAR(10)              NOT NULL,
    redirect_printer_id UUID,
    version             BIGINT                   NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT printer_pk PRIMARY KEY (id),
    CONSTRAINT printer_store_code_uk UNIQUE (store_id, code),
    -- target of the same-store foreign keys below and in the routing tables
    CONSTRAINT printer_id_store_uk UNIQUE (id, store_id),
    -- REDIRECT goes to another printer of the same store (D56)
    CONSTRAINT printer_redirect_same_store_fk FOREIGN KEY (redirect_printer_id, store_id)
        REFERENCES production.printer (id, store_id),
    CONSTRAINT printer_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT printer_host_not_blank_ck CHECK (btrim(host) <> ''),
    CONSTRAINT printer_port_ck CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT printer_code_page_ck CHECK (code_page IN ('CP437', 'CP850', 'CP860', 'WPC1252', 'CP858')),
    CONSTRAINT printer_mode_ck CHECK (mode IN ('NORMAL', 'HOLD', 'IGNORE', 'REDIRECT')),
    CONSTRAINT printer_redirect_ck CHECK ((mode = 'REDIRECT') = (redirect_printer_id IS NOT NULL)),
    CONSTRAINT printer_redirect_not_self_ck CHECK (redirect_printer_id <> id)
);
