package com.xavier.backend.identity.oidc.exception;

import com.xavier.backend.shared.exception.ConflictException;

/**
 * 409 {@code EXTERNAL_IDENTITY_EXISTS}: the account already has a link, or that IdP identity is linked to another
 * account (D144; {@code UNIQUE (account_id)} and {@code UNIQUE (issuer, subject)} decide a race).
 */
public class ExternalIdentityExistsException extends ConflictException {

    public static final String CODE = "EXTERNAL_IDENTITY_EXISTS";

    public ExternalIdentityExistsException() {
        super(CODE, "An identity-provider link already exists for this account or this identity. Remove it first.");
    }
}
