-- ADR 0044: a round's progress on the kitchen display (KDS), one row per BATCH ticket, written with the ticket. The
-- ticket stays append-only; this row is the operational state the kitchen moves: QUEUED -> IN_PREPARATION -> READY
-- -> BUMPED, a recall one step back. Each step keeps its time and employee; a recall clears the steps it undid. The
-- destination is the ticket's (a fact), copied for the station filter. Rounds received before this migration have no
-- row and are not on the KDS (no backfill).

-- the progress belongs to its ticket's store: the target of the composite foreign key below
ALTER TABLE production.production_ticket
    ADD CONSTRAINT production_ticket_id_store_uk UNIQUE (tenant_id, id, store_id);

CREATE TABLE production.kitchen_progress
(
    tenant_id   UUID                     NOT NULL DEFAULT shared.rls_tenant_id(),
    ticket_id   UUID                     NOT NULL,
    store_id    UUID                     NOT NULL,
    destination VARCHAR(40)              NOT NULL,
    status      VARCHAR(20)              NOT NULL,
    version     BIGINT                   NOT NULL,
    queued_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at  TIMESTAMP WITH TIME ZONE,
    started_by  UUID,
    ready_at    TIMESTAMP WITH TIME ZONE,
    ready_by    UUID,
    bumped_at   TIMESTAMP WITH TIME ZONE,
    bumped_by   UUID,
    recalled_at TIMESTAMP WITH TIME ZONE,
    recalled_by UUID,
    CONSTRAINT kitchen_progress_pk PRIMARY KEY (tenant_id, ticket_id),
    CONSTRAINT kitchen_progress_ticket_fk FOREIGN KEY (tenant_id, ticket_id, store_id)
        REFERENCES production.production_ticket (tenant_id, id, store_id),
    CONSTRAINT kitchen_progress_status_ck CHECK (status IN ('QUEUED', 'IN_PREPARATION', 'READY', 'BUMPED')),
    -- each step's time and employee go together, and exist exactly when the status has passed that step
    CONSTRAINT kitchen_progress_started_ck CHECK ((started_at IS NULL) = (status = 'QUEUED')
        AND (started_at IS NULL) = (started_by IS NULL)),
    CONSTRAINT kitchen_progress_ready_ck CHECK ((ready_at IS NULL) = (status IN ('QUEUED', 'IN_PREPARATION'))
        AND (ready_at IS NULL) = (ready_by IS NULL)),
    CONSTRAINT kitchen_progress_bumped_ck CHECK ((bumped_at IS NULL) = (status <> 'BUMPED')
        AND (bumped_at IS NULL) = (bumped_by IS NULL)),
    CONSTRAINT kitchen_progress_recalled_ck CHECK ((recalled_at IS NULL) = (recalled_by IS NULL))
);

-- a station's rounds by status, oldest first (D180: led by tenant_id)
CREATE INDEX kitchen_progress_station_ix
    ON production.kitchen_progress (tenant_id, store_id, destination, status, queued_at);
CREATE INDEX kitchen_progress_store_status_ix
    ON production.kitchen_progress (tenant_id, store_id, status, queued_at);

-- STORE class (ADR 0039) with the restrictive tenant policy (ADR 0040)
SELECT shared.rls_protect_store_table('production.kitchen_progress');
