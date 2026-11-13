-- R5 optional OIDC federation (D144, ADR 0034): a pre-provisioned BackOffice account linked by its owner to one
-- identity at the installation's one OpenID Provider. Authentication only: no role or claim is stored. Never a token.
-- A link is configuration, not a business fact: unlinking deletes the row (audited OIDC_UNLINKED).
CREATE TABLE identity.external_identity
(
    id                   UUID                     NOT NULL,
    account_id           UUID                     NOT NULL,
    issuer               VARCHAR(255)             NOT NULL,
    subject              VARCHAR(255)             NOT NULL,
    linked_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    linked_by_account_id UUID                     NOT NULL,
    last_login_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT external_identity_pk PRIMARY KEY (id),
    CONSTRAINT external_identity_account_fk FOREIGN KEY (account_id) REFERENCES identity.user_account (id),
    CONSTRAINT external_identity_linked_by_fk FOREIGN KEY (linked_by_account_id)
        REFERENCES identity.user_account (id),
    -- one IdP identity opens one account, and one account has one link (one provider per installation)
    CONSTRAINT external_identity_issuer_subject_uk UNIQUE (issuer, subject),
    CONSTRAINT external_identity_account_uk UNIQUE (account_id),
    CONSTRAINT external_identity_issuer_ck CHECK (btrim(issuer) <> ''),
    CONSTRAINT external_identity_subject_ck CHECK (btrim(subject) <> '')
);
