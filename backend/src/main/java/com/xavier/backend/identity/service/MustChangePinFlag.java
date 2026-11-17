package com.xavier.backend.identity.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Whether the employee of this request's POS session must change a manager-set PIN, as {@link PrincipalActors} read it
 * while building the actor (S77): the {@code MustChangePinFilter} then needs no transaction of its own. A request
 * attribute, so it holds for this request only; a role or PIN change applies from the next request, as before.
 */
public final class MustChangePinFlag {

    private static final String ATTRIBUTE = MustChangePinFlag.class.getName();

    private MustChangePinFlag() {
    }

    /** Records the flag on the current HTTP request, if there is one. */
    static void record(boolean mustChangePin) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.setAttribute(ATTRIBUTE, mustChangePin, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** The flag recorded for {@code request}; empty when its actor was not built from a POS session here. */
    public static Optional<Boolean> of(HttpServletRequest request) {
        return Optional.ofNullable((Boolean) request.getAttribute(ATTRIBUTE));
    }
}
