-- Support access (spec §5 "temporário, autorizado e auditado", P36, D83). A support account logs in only while an
-- administrator's grant is active. The flag never changes after creation, and a support account is never linked to
-- an employee: the employee's PIN would be a way into the POS that no grant controls.
ALTER TABLE identity.user_account ADD COLUMN support BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE identity.user_account
    ADD CONSTRAINT user_account_support_unlinked_ck CHECK (NOT support OR employee_id IS NULL);

-- ACTIVE (neither ended column set) → EXPIRED (expired_at, set by SupportGrantExpiry) or → REVOKED (revoked_at).
-- Login also checks valid_until > now, so a grant the job has not reached yet already refuses.
CREATE TABLE identity.support_grant
(
    id                    UUID                     NOT NULL,
    account_id            UUID                     NOT NULL,
    valid_from            TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until           TIMESTAMP WITH TIME ZONE NOT NULL,
    reason                VARCHAR(500)             NOT NULL,
    granted_by_account_id UUID                     NOT NULL,
    revoked_at            TIMESTAMP WITH TIME ZONE,
    revoked_by_account_id UUID,
    expired_at            TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    version               BIGINT                   NOT NULL,
    CONSTRAINT support_grant_pk PRIMARY KEY (id),
    CONSTRAINT support_grant_account_fk FOREIGN KEY (account_id) REFERENCES identity.user_account (id),
    CONSTRAINT support_grant_granted_by_fk FOREIGN KEY (granted_by_account_id) REFERENCES identity.user_account (id),
    CONSTRAINT support_grant_revoked_by_fk FOREIGN KEY (revoked_by_account_id) REFERENCES identity.user_account (id),
    -- SupportGrant.MAX_DURATION (P36)
    CONSTRAINT support_grant_max_24h_ck CHECK (valid_until > valid_from
        AND valid_until - valid_from <= interval '24 hours'),
    CONSTRAINT support_grant_reason_not_blank_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT support_grant_one_ending_ck CHECK (revoked_at IS NULL OR expired_at IS NULL),
    CONSTRAINT support_grant_revoked_by_ck CHECK ((revoked_at IS NULL) = (revoked_by_account_id IS NULL))
);

-- One ACTIVE grant per account.
CREATE UNIQUE INDEX support_grant_one_active_uk ON identity.support_grant (account_id)
    WHERE revoked_at IS NULL AND expired_at IS NULL;
-- The expiry job's scan.
CREATE INDEX support_grant_active_until_ix ON identity.support_grant (valid_until)
    WHERE revoked_at IS NULL AND expired_at IS NULL;
