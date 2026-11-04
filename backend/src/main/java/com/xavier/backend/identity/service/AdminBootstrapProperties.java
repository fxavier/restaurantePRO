package com.xavier.backend.identity.service;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code restaurantpro.bootstrap.admin.*}: the first administrator, created when the company is bootstrapped
 * (D30). Spring Boot's relaxed binding maps {@code RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME} and
 * {@code RESTAURANTPRO_BOOTSTRAP_ADMIN_INITIAL_PASSWORD_FILE} onto these directly; {@code application.yaml} has no
 * entry for them ({@code AdminBootstrapPropertiesBindingTest}).
 *
 * @param initialPasswordFile a secret file holding the initial password (one line); the admin must change it
 */
@ConfigurationProperties("restaurantpro.bootstrap.admin")
public record AdminBootstrapProperties(String username, Path initialPasswordFile) {

    boolean isComplete() {
        return username != null && !username.isBlank() && initialPasswordFile != null;
    }
}
