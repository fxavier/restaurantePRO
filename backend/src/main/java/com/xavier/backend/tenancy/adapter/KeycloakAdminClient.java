package com.xavier.backend.tenancy.adapter;

/**
 * One Keycloak service account the backend administers a realm with (D181, D182, O1 option 3): its server, realm,
 * client id and the file holding its private key (PEM PKCS#8, EC P-256; the client authenticates with
 * {@code private_key_jwt}, so no secret exists). Unset {@code baseUrl} or {@code keyFile} = not configured: every call
 * answers 503 {@code IDP_UNAVAILABLE} (fail closed).
 */
record KeycloakAdminClient(String baseUrl, String realm, String clientId, String keyFile) {

    boolean configured() {
        return baseUrl != null && !baseUrl.isBlank() && keyFile != null && !keyFile.isBlank() && realm != null
                && !realm.isBlank() && clientId != null && !clientId.isBlank();
    }

    /** The realm's issuer, also the audience of the client assertion (RFC 7523). */
    String issuer() {
        return baseUrl.replaceAll("/+$", "") + "/realms/" + realm;
    }

    String adminBase() {
        return baseUrl.replaceAll("/+$", "") + "/admin/realms/" + realm;
    }
}
