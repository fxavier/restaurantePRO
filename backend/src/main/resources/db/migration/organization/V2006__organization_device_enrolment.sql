-- R6 S73 (D182, D187): a store device (a POS terminal or a print bridge) is a Keycloak client of the devices realm
-- with private_key_jwt; there is no device token any more. The one-time enrolment code is split into a public selector
-- (the subject of a short-lived pending membership, which tells an unauthenticated enrolment its tenant) and a secret,
-- kept here as its SHA-256 only; five failed attempts burn the code. The first attempt with the right secret pins the
-- device's public key (its RFC 7638 thumbprint), so a second device holding the same code cannot take over the
-- enrolment between the key's registration at Keycloak and the commit. A used code keeps its hash and that key until
-- it expires or is replaced, so the same device retrying after a lost answer gets the same answer (G5 review 5). The device's own TERMINAL or BRIDGE membership
-- is kept so revocation revokes it in the same transaction.
-- Runs after V2005: it drops the device-token key V2005 rebuilt as UNIQUE (tenant_id, device_token_hash).
ALTER TABLE organization.terminal
    DROP CONSTRAINT terminal_active_has_token_ck,
    DROP CONSTRAINT terminal_device_token_hash_format_ck,
    DROP CONSTRAINT terminal_device_token_hash_uk,
    DROP CONSTRAINT terminal_enrollment_code_only_registered_ck;
ALTER TABLE organization.terminal
    DROP COLUMN device_token_hash;

-- every device before S73 was a POS terminal; the default stays (a terminal is the device registered by default)
ALTER TABLE organization.terminal
    ADD COLUMN kind                       VARCHAR(20) NOT NULL DEFAULT 'POS',
    ADD COLUMN enrollment_failures        INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN enrollment_key_thumbprint  VARCHAR(43),
    ADD COLUMN enrollment_membership_id   UUID,
    ADD COLUMN enrollment_completed_at    TIMESTAMP WITH TIME ZONE,
    ADD COLUMN membership_id              UUID;
ALTER TABLE organization.terminal
    ADD CONSTRAINT terminal_kind_ck CHECK (kind IN ('POS', 'PRINT_BRIDGE')),
    ADD CONSTRAINT terminal_enrollment_failures_ck CHECK (enrollment_failures BETWEEN 0 AND 4),
    ADD CONSTRAINT terminal_enrollment_key_thumbprint_format_ck
        CHECK (enrollment_key_thumbprint ~ '^[A-Za-z0-9_-]{43}$'),
    -- a revoked device holds no code; a pinned key and the pending membership exist only with a code
    ADD CONSTRAINT terminal_enrollment_code_not_revoked_ck CHECK (status <> 'REVOKED' OR enrollment_code_hash IS NULL),
    ADD CONSTRAINT terminal_enrollment_key_needs_code_ck
        CHECK (enrollment_key_thumbprint IS NULL OR enrollment_code_hash IS NOT NULL),
    -- a used code keeps its secret's hash and the key it enrolled, to replay that enrolment after a lost answer
    ADD CONSTRAINT terminal_enrollment_completed_has_key_ck
        CHECK (enrollment_completed_at IS NULL OR enrollment_key_thumbprint IS NOT NULL),
    -- NOT VALID: a code issued before this migration has no pending membership; it can never be redeemed (nothing
    -- resolves its tenant) and is replaced or cleared by the next write of its row, which the check then covers. No
    -- UPDATE here (D180 recipe).
    ADD CONSTRAINT terminal_enrollment_membership_needs_code_ck
        CHECK ((enrollment_membership_id IS NULL) = (enrollment_code_hash IS NULL)) NOT VALID;
