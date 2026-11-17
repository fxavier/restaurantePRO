package com.xavier.bff;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * @param backendUrl         the restaurantPRO backend that every relayed call goes to
 * @param clientKeyFile      a private JWK for {@code private_key_jwt}; read once at startup, its content never logged
 * @param backendTimeout     how long one relayed call may take
 * @param idpConnectTimeout  connect timeout of the BFF's own calls to Keycloak (token, refresh, logout)
 * @param idpReadTimeout     read timeout of those calls
 * @param maxRequestBody     the largest request body the BFF relays; more is a 413
 */
@ConfigurationProperties("restaurantpro.bff")
public record BffProperties(URI backendUrl, Path clientKeyFile, Duration backendTimeout,
                            Duration idpConnectTimeout, Duration idpReadTimeout, DataSize maxRequestBody) {
}
