-- The persistent print queue (spec §4, §7.2; r2-decisions §4 production, D50 §7.2): one row per physical delivery
-- attempt of a ticket. QUEUED -> PROCESSING -> SENT | RETRY_WAIT | FAILED | UNKNOWN, RETRY_WAIT -> PROCESSING.
-- dedupe_key makes every job creation idempotent (ticket:<id>, retry:<job id>, copy:<ticket id>:<n>); a manual retry
-- is a new row with retry_of, so a FAILED or UNKNOWN attempt is never rewritten.
CREATE TABLE production.print_job
(
    id              UUID                     NOT NULL,
    store_id        UUID                     NOT NULL,
    ticket_id       UUID                     NOT NULL,
    printer_id      UUID                     NOT NULL,
    kind            VARCHAR(10)              NOT NULL,
    status          VARCHAR(12)              NOT NULL,
    attempts        INTEGER                  NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE,
    sent_at         TIMESTAMP WITH TIME ZONE,
    last_error      VARCHAR(40),
    dedupe_key      VARCHAR(120)             NOT NULL,
    retry_of        UUID,
    resolution      VARCHAR(20),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL,
    CONSTRAINT print_job_pk PRIMARY KEY (id),
    CONSTRAINT print_job_dedupe_key_uk UNIQUE (dedupe_key),
    CONSTRAINT print_job_ticket_fk FOREIGN KEY (ticket_id) REFERENCES production.production_ticket (id),
    CONSTRAINT print_job_printer_fk FOREIGN KEY (printer_id, store_id) REFERENCES production.printer (id, store_id),
    CONSTRAINT print_job_retry_of_fk FOREIGN KEY (retry_of) REFERENCES production.print_job (id),
    CONSTRAINT print_job_kind_ck CHECK (kind IN ('ORIGINAL', 'COPY')),
    CONSTRAINT print_job_status_ck
        CHECK (status IN ('QUEUED', 'PROCESSING', 'SENT', 'RETRY_WAIT', 'FAILED', 'UNKNOWN')),
    CONSTRAINT print_job_attempts_ck CHECK (attempts BETWEEN 0 AND 5),
    CONSTRAINT print_job_queued_ck CHECK (status <> 'QUEUED' OR attempts = 0),
    CONSTRAINT print_job_started_ck CHECK (status = 'QUEUED' OR started_at IS NOT NULL),
    CONSTRAINT print_job_sent_ck CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT print_job_resolution_ck CHECK (resolution IS NULL OR status IN ('FAILED', 'UNKNOWN')),
    CONSTRAINT print_job_acknowledged_ck CHECK (resolution IS DISTINCT FROM 'CONFIRMED_PRINTED' OR status = 'UNKNOWN'),
    CONSTRAINT print_job_resolution_value_ck CHECK (resolution IN ('CONFIRMED_PRINTED', 'RETRIED'))
);

-- one retry per attempt
CREATE UNIQUE INDEX print_job_retry_of_uk ON production.print_job (retry_of) WHERE retry_of IS NOT NULL;
-- the worker's due work (D50 §7.2)
CREATE INDEX print_job_due_ix ON production.print_job (next_attempt_at) WHERE status IN ('QUEUED', 'RETRY_WAIT');
-- the lease sweeper's candidates (a crash during a send)
CREATE INDEX print_job_processing_ix ON production.print_job (started_at) WHERE status = 'PROCESSING';
CREATE INDEX print_job_printer_status_ix ON production.print_job (printer_id, status);
CREATE INDEX print_job_store_status_created_ix ON production.print_job (store_id, status, created_at);
CREATE INDEX print_job_ticket_ix ON production.print_job (ticket_id);
