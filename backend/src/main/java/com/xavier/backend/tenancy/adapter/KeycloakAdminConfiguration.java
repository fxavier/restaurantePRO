package com.xavier.backend.tenancy.adapter;

import com.xavier.backend.tenancy.IdentityProviderAdmin;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The identity-provider adapter's two service accounts (O1 option 3): {@code restaurantpro.keycloak.admin.*}
 * ({@code tenancy-provisioner} in the tenant realm, declared by S71) and {@code restaurantpro.keycloak.devices-admin.*}
 * ({@code device-provisioner} in {@code restaurantpro-devices}). Either unset ({@code base-url} or {@code key-file}
 * empty) fails closed: its calls answer 503 {@code IDP_UNAVAILABLE}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeycloakAdminConfiguration.KeycloakAdminProperties.class)
class KeycloakAdminConfiguration {

    static final String DEVICE_REALM = "restaurantpro-devices";
    static final String DEVICE_PROVISIONER = "device-provisioner";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @ConfigurationProperties("restaurantpro.keycloak")
    record KeycloakAdminProperties(Account admin, Account devicesAdmin) {

        record Account(String baseUrl, String realm, String clientId, String keyFile) {
        }

        KeycloakAdminClient tenantProvisioner() {
            Account account = admin == null ? new Account(null, null, null, null) : admin;
            return new KeycloakAdminClient(account.baseUrl(), account.realm(), account.clientId(), account.keyFile());
        }

        KeycloakAdminClient deviceProvisioner() {
            Account account = devicesAdmin == null ? new Account(null, null, null, null) : devicesAdmin;
            return new KeycloakAdminClient(account.baseUrl(), orDefault(account.realm(), DEVICE_REALM),
                    orDefault(account.clientId(), DEVICE_PROVISIONER), account.keyFile());
        }

        private static String orDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    @Bean
    IdentityProviderAdmin identityProviderAdmin(KeycloakAdminProperties properties, Clock clock) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build());
        requests.setReadTimeout(READ_TIMEOUT);
        RestClient.Builder http = RestClient.builder().requestFactory(requests);
        return new KeycloakIdentityProviderAdmin(new RealmAdmin(http, properties.tenantProvisioner(), clock),
                new RealmAdmin(http, properties.deviceProvisioner(), clock));
    }
}
