package com.xavier.backend.tenancy.exception;

import com.xavier.backend.shared.exception.UnavailableException;
import java.util.Map;

/** The identity provider's admin API cannot be reached (503 {@code IDP_UNAVAILABLE}); nothing was changed. */
public class IdentityProviderUnavailableException extends UnavailableException {

    public static final String CODE = "IDP_UNAVAILABLE";

    public IdentityProviderUnavailableException() {
        super(CODE, "The identity provider is unavailable; try again later.", Map.of());
    }
}
