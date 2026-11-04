-- POS stations (spec §8.2, D26). Only SHA-256 hex hashes of the enrollment code and device token are
-- stored; the format checks make storing a plaintext secret impossible.
CREATE TABLE organization.terminal
(
    id                         UUID                     NOT NULL,
    store_id                   UUID                     NOT NULL,
    code                       VARCHAR(20)              NOT NULL,
    name                       VARCHAR(120)             NOT NULL,
    status                     VARCHAR(20)              NOT NULL,
    enrollment_code_hash       VARCHAR(64),
    enrollment_code_expires_at TIMESTAMP WITH TIME ZONE,
    device_token_hash          VARCHAR(64),
    version                    BIGINT                   NOT NULL,
    created_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT terminal_pk PRIMARY KEY (id),
    CONSTRAINT terminal_store_fk FOREIGN KEY (store_id) REFERENCES organization.store (id),
    CONSTRAINT terminal_store_code_uk UNIQUE (store_id, code),
    CONSTRAINT terminal_enrollment_code_hash_uk UNIQUE (enrollment_code_hash),
    CONSTRAINT terminal_device_token_hash_uk UNIQUE (device_token_hash),
    CONSTRAINT terminal_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT terminal_name_not_blank_ck CHECK (btrim(name) <> ''),
    CONSTRAINT terminal_status_ck CHECK (status IN ('REGISTERED', 'ACTIVE', 'REVOKED')),
    CONSTRAINT terminal_enrollment_code_hash_format_ck CHECK (enrollment_code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT terminal_device_token_hash_format_ck CHECK (device_token_hash ~ '^[0-9a-f]{64}$'),
    -- a code always has an expiry, and only a terminal that is not enrolled yet holds one
    CONSTRAINT terminal_enrollment_code_expiry_ck
        CHECK ((enrollment_code_hash IS NULL) = (enrollment_code_expires_at IS NULL)),
    CONSTRAINT terminal_enrollment_code_only_registered_ck
        CHECK (status = 'REGISTERED' OR enrollment_code_hash IS NULL),
    CONSTRAINT terminal_active_has_token_ck CHECK (status <> 'ACTIVE' OR device_token_hash IS NOT NULL)
);
