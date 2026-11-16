package com.xavier.backend.tenancy;

import java.time.Instant;
import java.util.List;

/**
 * The port to the identity provider's admin API (D175, D181, D182, D187), used through the {@code tenancy-provisioner}
 * service account only: organizations, organization members, user invitations (execute-actions email), user sessions
 * and the {@code pos-*}/{@code bridge-*} clients. Keycloak never owns authority (D175): nothing here grants a
 * permission. Every call is an external call (D48: persist, call, record), never inside a transaction. Until S75 ships
 * the {@code RestClient} adapter every method answers 503 {@code IDP_UNAVAILABLE}.
 */
public interface IdentityProviderAdmin {

    /** @return the new organization's id (stored as {@code tenancy.tenant.keycloak_org_id}) */
    String createOrganization(String alias, String name);

    void addOrganizationMember(String organizationId, String userId);

    /**
     * Creates (or finds) the user and sends the execute-actions email (verify email, set password, configure OTP).
     *
     * @return the user's id (the JWT {@code sub})
     */
    String inviteUser(String email, String organizationId);

    /** The user's open sessions (§5 session listing). */
    List<IdentitySession> userSessions(String userId);

    /** Ends every session of the user. */
    void logoutUser(String userId);

    /**
     * Creates a confidential client authenticated with {@code client-jwt} (private_key_jwt, no secret).
     *
     * @return the service-account user id (the {@code sub} of the client's tokens)
     */
    String createClient(String clientId);

    /** Registers the device's public key (a JWK) on the client. */
    void registerClientKey(String clientId, String publicJwk);

    void disableClient(String clientId);

    /** One session of a user, as the identity provider lists it. */
    record IdentitySession(String id, String ipAddress, Instant started, Instant lastAccess) {
    }
}
