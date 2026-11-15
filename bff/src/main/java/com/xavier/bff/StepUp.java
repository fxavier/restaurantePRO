package com.xavier.bff;

import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A step-up asked by the SPA after an RFC 9470 challenge (D184 amendment): the level and maximum
 * age to ask Keycloak for, and the same-origin path to come back to. The BFF never replays a request.
 */
record StepUp(String acrValues, String maxAge, String returnPath) implements Serializable {

    private static final Set<String> LEVELS = Set.of("1", "2");
    private static final Pattern MAX_AGE = Pattern.compile("[0-9]{1,5}");
    private static final String PENDING = StepUp.class.getName() + ".PENDING";
    private static final String RETURN = StepUp.class.getName() + ".RETURN";

    static final class InvalidStepUpException extends RuntimeException {
        InvalidStepUpException(String message) {
            super(message);
        }
    }

    static StepUp parse(String acrValues, String maxAge, String returnPath) {
        if (!LEVELS.contains(acrValues)) {
            throw new InvalidStepUpException("acr_values must be 1 or 2");
        }
        if (!MAX_AGE.matcher(maxAge).matches()) {
            throw new InvalidStepUpException("max_age must be a number of seconds");
        }
        if (!isSameOriginPath(returnPath)) {
            throw new InvalidStepUpException("return must be a path on this origin");
        }
        return new StepUp(acrValues, maxAge, returnPath);
    }

    /**
     * "/x" only. A browser reads "//host" and "///host" as another origin, and java.net.URI parses "///host"
     * without an authority, hence the explicit "//" check; URI refuses a backslash or a control character.
     */
    private static boolean isSameOriginPath(String path) {
        if (!path.startsWith("/") || path.startsWith("//")) {
            return false;
        }
        try {
            URI uri = new URI(path);
            return uri.getScheme() == null && uri.getRawAuthority() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    void rememberIn(HttpSession session) {
        session.setAttribute(PENDING, this);
    }

    /** The step-up to ask for in the next authorization request, at most once. */
    static StepUp takePending(HttpSession session) {
        StepUp pending = (StepUp) session.getAttribute(PENDING);
        session.removeAttribute(PENDING);
        if (pending != null) {
            session.setAttribute(RETURN, pending.returnPath());
        }
        return pending;
    }

    /** Where the browser goes after the step-up login, at most once. */
    static String takeReturnPath(HttpSession session) {
        String path = (String) session.getAttribute(RETURN);
        session.removeAttribute(RETURN);
        return path;
    }
}
