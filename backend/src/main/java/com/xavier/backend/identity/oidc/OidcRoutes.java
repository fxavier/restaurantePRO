package com.xavier.backend.identity.oidc;

/** The fixed OIDC routes (PRD R5 S57), all under the open {@code /api/v1/auth/**}. */
public final class OidcRoutes {

    public static final String BASE = "/api/v1/auth/oidc";
    public static final String AUTHORIZE = BASE + "/authorize";
    public static final String CALLBACK = BASE + "/callback";
    public static final String LINK = BASE + "/link";
    /** The SPA's BackOffice login page (served by the frontend, not the backend). */
    public static final String LOGIN_PAGE = "/backoffice/login";
    /** Where a refused or failed federated login lands; the login page shows the error. */
    public static final String LOGIN_ERROR = LOGIN_PAGE + "?error=oidc";

    private OidcRoutes() {
    }
}
