package com.xavier.bff;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * @param backendUrl     the restaurantPRO backend that every relayed call goes to
 * @param clientKeyFile  a private JWK for {@code private_key_jwt}; read once at startup, its content never logged
 * @param backendTimeout how long one relayed call may take
 */
@ConfigurationProperties("restaurantpro.bff")
public record BffProperties(URI backendUrl, Path clientKeyFile, Duration backendTimeout) {
}
