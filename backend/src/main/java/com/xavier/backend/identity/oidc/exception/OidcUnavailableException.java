package com.xavier.backend.identity.oidc.exception;

import com.xavier.backend.shared.exception.UnavailableException;
import java.util.Map;

/**
 * The OpenID Provider did not answer its discovery request (D144): the OIDC routes answer 503 until it does; password
 * login never depends on it (P56). The next request tries again.
 */
public class OidcUnavailableException extends UnavailableException {

    public static final String CODE = "OIDC_UNAVAILABLE";

    public OidcUnavailableException() {
        super(CODE, "The identity provider cannot be reached. Try again later, or log in with your password.",
                Map.of());
    }
}
