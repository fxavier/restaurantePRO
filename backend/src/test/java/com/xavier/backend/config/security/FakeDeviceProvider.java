package com.xavier.backend.config.security;

import com.xavier.backend.tenancy.IdentityProviderAdmin;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The devices realm's admin port, faked (D182, D187): a client's service-account user is {@code svc-<clientId>}, the
 * last key registered on a client is kept, and a disabled client is remembered. The tenant-realm operations are not
 * part of device suites and fail loudly.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeDeviceProvider {

    /** clientId → the public JWK last registered. */
    public static final Map<String, String> KEYS = new ConcurrentHashMap<>();
    public static final Set<String> CREATED = ConcurrentHashMap.newKeySet();
    public static final Set<String> DISABLED = ConcurrentHashMap.newKeySet();

    /** The subject of the tokens of {@code clientId}. */
    public static String subjectOf(String clientId) {
        return com.xavier.backend.identity.IdentityFixtures.Terminal.subjectOf(clientId);
    }

    @Bean
    @Primary
    IdentityProviderAdmin fakeDeviceProvider() {
        return new IdentityProviderAdmin() {
            @Override
            public String createOrganization(String alias, String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void addOrganizationMember(String organizationId, String userId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String inviteUser(String email, String organizationId) {
                throw new UnsupportedOperationException();
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
                CREATED.add(clientId);
                return subjectOf(clientId);
            }

            @Override
            public void registerClientKey(String clientId, String publicJwk) {
                KEYS.put(clientId, publicJwk);
            }

            @Override
            public void disableClient(String clientId) {
                DISABLED.add(clientId);
            }
        };
    }
}
