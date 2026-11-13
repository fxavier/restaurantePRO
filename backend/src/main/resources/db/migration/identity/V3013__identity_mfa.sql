-- R5 native TOTP second factor (D139, D141, D143). The TOTP secret is stored sealed (AES-256-GCM, key outside the
-- database), recovery and enrolment codes only as SHA-256 hashes. Nothing here is ever logged or audited.

-- PENDING (enrolment started, pending_expires_at set) -> ACTIVE (confirmed) -> REVOKED (admin reset or break-glass).
-- A PENDING factor that is replaced or reset is deleted: it never proved anything.
CREATE TABLE identity.mfa_factor
(
    id                    UUID                     NOT NULL,
    account_id            UUID                     NOT NULL,
    kind                  VARCHAR(10)              NOT NULL,
    status                VARCHAR(10)              NOT NULL,
    secret_ciphertext     BYTEA                    NOT NULL,
    secret_nonce          BYTEA                    NOT NULL,
    key_id                VARCHAR(20)              NOT NULL,
    -- the last TOTP step accepted: a code is accepted only for a later step (replay, D139)
    last_used_step        BIGINT                   NOT NULL DEFAULT 0,
    failed_attempts       INT                      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMP WITH TIME ZONE,
    pending_expires_at    TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_at          TIMESTAMP WITH TIME ZONE,
    revoked_at            TIMESTAMP WITH TIME ZONE,
    revoked_by_account_id UUID, -- NULL with revoked_at set = the break-glass console reset
    revoke_reason         VARCHAR(500),
    version               BIGINT                   NOT NULL,
    CONSTRAINT mfa_factor_pk PRIMARY KEY (id),
    CONSTRAINT mfa_factor_account_fk FOREIGN KEY (account_id) REFERENCES identity.user_account (id),
    CONSTRAINT mfa_factor_revoked_by_fk FOREIGN KEY (revoked_by_account_id) REFERENCES identity.user_account (id),
    CONSTRAINT mfa_factor_kind_ck CHECK (kind = 'TOTP'),
    CONSTRAINT mfa_factor_status_ck CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED')),
    CONSTRAINT mfa_factor_failed_attempts_ck CHECK (failed_attempts >= 0),
    CONSTRAINT mfa_factor_last_used_step_ck CHECK (last_used_step >= 0),
    CONSTRAINT mfa_factor_nonce_ck CHECK (octet_length(secret_nonce) = 12),
    CONSTRAINT mfa_factor_key_id_ck CHECK (btrim(key_id) <> ''),
    CONSTRAINT mfa_factor_pending_ck CHECK ((status = 'PENDING') = (pending_expires_at IS NOT NULL)),
    CONSTRAINT mfa_factor_active_ck CHECK (status <> 'ACTIVE' OR activated_at IS NOT NULL),
    CONSTRAINT mfa_factor_revoked_ck CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CONSTRAINT mfa_factor_revoke_reason_ck CHECK (revoked_at IS NULL OR btrim(revoke_reason) <> '')
);

-- One ACTIVE and one PENDING factor per account.
CREATE UNIQUE INDEX mfa_factor_one_active_uk ON identity.mfa_factor (account_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX mfa_factor_one_pending_uk ON identity.mfa_factor (account_id) WHERE status = 'PENDING';

-- Ten per enrolment, each usable once (used_at, compare-and-set). Regenerating replaces them all.
CREATE TABLE identity.mfa_recovery_code
(
    id        UUID                     NOT NULL,
    factor_id UUID                     NOT NULL,
    code_hash CHAR(64)                 NOT NULL,
    used_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT mfa_recovery_code_pk PRIMARY KEY (id),
    CONSTRAINT mfa_recovery_code_factor_fk FOREIGN KEY (factor_id) REFERENCES identity.mfa_factor (id),
    CONSTRAINT mfa_recovery_code_uk UNIQUE (factor_id, code_hash),
    CONSTRAINT mfa_recovery_code_hash_ck CHECK (code_hash ~ '^[0-9a-f]{64}$')
);

-- One-time, 24 h, issued by an administrator's reset and handed over out of band (D143). A newer reset voids the
-- older code (used_at set), so an account has at most one usable code.
CREATE TABLE identity.mfa_enrolment_code
(
    id                   UUID                     NOT NULL,
    account_id           UUID                     NOT NULL,
    code_hash            CHAR(64)                 NOT NULL,
    expires_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at              TIMESTAMP WITH TIME ZONE,
    issued_by_account_id UUID                     NOT NULL,
    reason               VARCHAR(500)             NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT mfa_enrolment_code_pk PRIMARY KEY (id),
    CONSTRAINT mfa_enrolment_code_account_fk FOREIGN KEY (account_id) REFERENCES identity.user_account (id),
    CONSTRAINT mfa_enrolment_code_issued_by_fk FOREIGN KEY (issued_by_account_id)
        REFERENCES identity.user_account (id),
    CONSTRAINT mfa_enrolment_code_hash_uk UNIQUE (code_hash),
    CONSTRAINT mfa_enrolment_code_hash_ck CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT mfa_enrolment_code_reason_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT mfa_enrolment_code_expiry_ck CHECK (expires_at > created_at)
);
CREATE UNIQUE INDEX mfa_enrolment_code_one_open_uk ON identity.mfa_enrolment_code (account_id)
    WHERE used_at IS NULL;

-- Break-glass (D143): the last administrator lost the phone and the codes. Run only by deploy/mfa-break-glass.sh,
-- in one transaction with audit.record_console_event, so each module's schema is written only by its own function
-- (D6). Revokes the ACTIVE factor of the (normalized) username and returns the account id; raises when the username
-- is unknown, has no active factor, or the reason is blank. Sessions are not touched: an account that logs in
-- afterwards has no factor and enrols again.
CREATE FUNCTION identity.mfa_break_glass_reset(p_username text, p_reason text) RETURNS uuid
    LANGUAGE plpgsql AS
$$
DECLARE
    v_account uuid;
BEGIN
    IF p_reason IS NULL OR btrim(p_reason) = '' THEN
        RAISE EXCEPTION 'identity.mfa_break_glass_reset: a reason is required' USING ERRCODE = 'check_violation';
    END IF;
    SELECT id INTO v_account FROM identity.user_account WHERE username = lower(btrim(p_username));
    IF v_account IS NULL THEN
        RAISE EXCEPTION 'identity.mfa_break_glass_reset: unknown username' USING ERRCODE = 'no_data_found';
    END IF;
    UPDATE identity.mfa_factor
    SET status        = 'REVOKED',
        revoked_at    = now(),
        revoke_reason = left(btrim(p_reason), 500),
        locked_until  = NULL,
        version       = version + 1
    WHERE account_id = v_account
      AND status = 'ACTIVE';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'identity.mfa_break_glass_reset: the account has no active second factor'
            USING ERRCODE = 'no_data_found';
    END IF;
    RETURN v_account;
END;
$$;
