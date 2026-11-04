package com.xavier.backend.organization.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code restaurantpro.bootstrap.company.*}: the company created at first startup (D30). Bound from the
 * {@code RESTAURANTPRO_BOOTSTRAP_COMPANY_*} environment variables via {@code application.yaml}.
 * Values are checked when the company is created, not here.
 */
@ConfigurationProperties("restaurantpro.bootstrap.company")
public record CompanyBootstrapProperties(String name, String taxId, String country, String currency) {

    boolean isUnset() {
        return isBlank(name) && isBlank(taxId) && isBlank(country) && isBlank(currency);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
