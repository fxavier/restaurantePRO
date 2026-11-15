package com.xavier.backend.tenancy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The identity provider port faked at the module boundary (Keycloak itself is proven by {@code IdentityProviderAdminIT}):
 * organizations are created with id {@code org-<alias>} and every call is recorded. Only the organization half of the
 * port is used by the platform command; the rest answers as an empty Keycloak would.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingIdentityProvider {

    public static final List<String> ORGANIZATIONS_CREATED = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    IdentityProviderAdmin recordingIdentityProviderAdmin() {
        return new IdentityProviderAdmin() {
            @Override
            public String createOrganization(String alias, String name) {
                ORGANIZATIONS_CREATED.add(alias);
                return "org-" + alias;
            }

            @Override
            public void addOrganizationMember(String organizationId, String userId) {
            }

            @Override
            public String inviteUser(String email, String organizationId) {
                return "user-" + email;
            }

            @Override
            public List<IdentitySession> userSessions(String userId) {
                return List.of();
            }

            @Override
            public void logoutUser(String userId) {
            }

            @Override
            public String createClient(String clientId) {
                return "service-account-" + clientId;
            }

            @Override
            public void registerClientKey(String clientId, String publicJwk) {
            }

            @Override
            public void disableClient(String clientId) {
            }
        };
    }
}
