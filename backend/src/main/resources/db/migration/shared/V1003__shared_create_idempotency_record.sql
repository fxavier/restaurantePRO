-- Idempotency-Key store (D24, spec §9). One row per (scope, key); the primary key is what makes a
-- concurrent duplicate lose: only one INSERT ... ON CONFLICT DO NOTHING can claim the key.
CREATE TABLE shared.idempotency_record
(
    scope           VARCHAR(200)             NOT NULL,
    key             VARCHAR(255)             NOT NULL,
    request_hash    CHAR(64)                 NOT NULL,
    status          VARCHAR(16)              NOT NULL,
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT idempotency_record_pk PRIMARY KEY (scope, key),
    CONSTRAINT idempotency_record_status_ck CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_record_completed_ck CHECK (
        (status = 'IN_PROGRESS' AND response_status IS NULL AND completed_at IS NULL)
            OR (status = 'COMPLETED' AND response_status IS NOT NULL AND completed_at IS NOT NULL))
);
