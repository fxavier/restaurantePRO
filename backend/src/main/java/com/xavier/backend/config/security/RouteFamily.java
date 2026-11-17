package com.xavier.backend.config.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The route families of the API and who may call each (D176): the realms whose tokens a family accepts and the
 * authorized party ({@code azp}, the Keycloak client) its tokens must come from. One place for every path rule.
 */
enum RouteFamily {

    /** {@code /backoffice/**} and {@code /auth/**} (except the POS routes under it): the BFF only. */
    BACKOFFICE(EnumSet.of(Realm.TENANT, Realm.OPS), "backoffice-bff"::equals),
    /** {@code /bridge/**}: print bridges, {@code bridge-<id>} clients of the devices realm (D187). */
    BRIDGE(EnumSet.of(Realm.DEVICES), azp -> azp.startsWith("bridge-")),
    /**
     * {@code /platform/**} (S75): the ops realm with the {@code platform-operator} role ({@code TenantResolutionFilter};
     * a valid token of the tenant realm is 403 {@code PLATFORM_FORBIDDEN}, not 401). The ops console's client is not
     * decided yet (S75), so the authorized party is not restricted here; the issuer and the role are.
     */
    PLATFORM(EnumSet.of(Realm.TENANT, Realm.OPS), azp -> true),
    /**
     * {@code /pos/**} and the POS session routes under {@code /auth}: terminals, {@code pos-<id>} clients of the devices
     * realm (D182); the employee's POS session ({@code X-Pos-Session}) rides on the terminal's token.
     */
    POS(EnumSet.of(Realm.DEVICES), azp -> azp.startsWith("pos-"));

    static final String API = "/api/v1/";
    static final String TENANT_LIST = "/api/v1/auth/tenants";
    /** The PIN login: the terminal's token only, no POS session yet. */
    static final String POS_LOGIN = "/api/v1/auth/pos-login";
    /** The POS session routes under {@code /auth} (R2 paths kept): the PIN login, PIN choice, session and logout. */
    static final Set<String> POS_AUTH_ROUTES = Set.of(POS_LOGIN, "/api/v1/auth/pos-pin", "/api/v1/auth/pos-logout",
            "/api/v1/auth/pos-session");
    /** A device's enrolment with its one-time code (D182): no token at all, the code is the credential. */
    static final String[] ENROLMENT_ROUTES = {"/api/v1/pos/enrolment", "/api/v1/bridge/enrolment"};

    private final Set<Realm> realms;
    private final Predicate<String> authorizedParty;

    RouteFamily(Set<Realm> realms, Predicate<String> authorizedParty) {
        this.realms = realms;
        this.authorizedParty = authorizedParty;
    }

    Set<Realm> realms() {
        return realms;
    }

    boolean allowsAuthorizedParty(String azp) {
        return azp != null && authorizedParty.test(azp);
    }

    /** The family of an API path; empty outside {@code /api/v1/}. */
    static Optional<RouteFamily> of(String path) {
        if (path.startsWith(API + "pos/") || POS_AUTH_ROUTES.contains(path)) {
            return Optional.of(POS);
        }
        if (path.startsWith(API + "bridge/")) {
            return Optional.of(BRIDGE);
        }
        if (path.startsWith(API + "platform/")) {
            return Optional.of(PLATFORM);
        }
        if (path.startsWith(API)) {
            return Optional.of(BACKOFFICE);
        }
        return Optional.empty();
    }

    static Optional<RouteFamily> of(HttpServletRequest request) {
        return of(pathOf(request));
    }

    static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

}
