package com.xavier.backend.identity.oidc.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.Map;
import java.util.UUID;

/** 404 {@code EXTERNAL_IDENTITY_NOT_FOUND}: the account has no identity-provider link. */
public class ExternalIdentityNotFoundException extends NotFoundException {

    public static final String CODE = "EXTERNAL_IDENTITY_NOT_FOUND";

    public ExternalIdentityNotFoundException(UUID accountId) {
        super(CODE, "Account " + accountId + " has no identity-provider link.", Map.of("accountId", accountId));
    }
}
