package com.xavier.backend.fiscal.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code restaurantpro.fiscal.*} (D54, B4). {@code adapter} selects the {@link com.xavier.backend.fiscal.FiscalAdapter}
 * by code (default {@code internal}). {@code allowNonCertified} must be set explicitly to start with a non-certified
 * adapter; it is {@code false} by default and must stay so in production. The environment variable
 * {@code RESTAURANTPRO_FISCAL_ALLOW_NON_CERTIFIED} binds to it.
 */
@ConfigurationProperties("restaurantpro.fiscal")
public record FiscalProperties(@DefaultValue("internal") String adapter, boolean allowNonCertified) {
}
