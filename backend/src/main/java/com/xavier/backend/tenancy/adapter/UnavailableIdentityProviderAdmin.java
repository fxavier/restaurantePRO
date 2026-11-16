package com.xavier.backend.tenancy.adapter;

import com.xavier.backend.tenancy.IdentityProviderAdmin;
import com.xavier.backend.tenancy.exception.IdentityProviderUnavailableException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The placeholder port of S71: every call fails with 503 {@code IDP_UNAVAILABLE} (fail closed), so no code path can
 * believe a Keycloak change happened. S75 replaces it with the {@code RestClient} adapter (no admin client library).
 */
@Component
class UnavailableIdentityProviderAdmin implements IdentityProviderAdmin {

    @Override
    public String createOrganization(String alias, String name) {
        throw new IdentityProviderUnavailableException();
    }

    @Override
    public void addOrganizationMember(String organizationId, String userId) {
        throw new IdentityProviderUnavailableException();
    }

    @Override
    public String inviteUser(String email, String organizationId) {
        throw new IdentityProviderUnavailableException();
    }

    @Override
    public List<IdentitySession> userSessions(String userId) {
        throw new IdentityProviderUnavailableException();
    }

    @Override
    public void logoutUser(String userId) {
        throw new IdentityProviderUnavailableException();
    }

    @Override
    public String createClient(String clientId) {
        throw new IdentityProviderUnavailableException();
    }

    @Override
    public void registerClientKey(String clientId, String publicJwk) {
        throw new IdentityProviderUnavailableException();
    }

    @Override
    public void disableClient(String clientId) {
        throw new IdentityProviderUnavailableException();
    }
}
