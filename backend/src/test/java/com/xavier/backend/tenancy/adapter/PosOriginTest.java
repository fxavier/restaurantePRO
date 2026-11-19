package com.xavier.backend.tenancy.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@code restaurantpro.pos.origin} is checked when the adapter is built, so a value Keycloak would refuse (or should
 * not trust) fails the startup instead of the first enrolment (FE-F01, FE-F02, FE-F03).
 */
@ExtendWith(OutputCaptureExtension.class)
class PosOriginTest {

    /** Browsers send the origin lowercased; Keycloak compares it exactly, so "Invalid origin" would follow. */
    @Test
    void anUppercaseOriginIsLowercased() {
        assertThat(KeycloakIdentityProviderAdmin.posOrigin("HTTPS://POS.RestaurantPro.Test:8443"))
                .isEqualTo("https://pos.restaurantpro.test:8443");
    }

    @Test
    void plainHttpIsRefusedAtStartupExceptOnTheLoopback() {
        assertThatThrownBy(() -> new KeycloakIdentityProviderAdmin(null, null, "http://pos.example"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
        for (String loopback : List.of("http://localhost:5173", "http://127.0.0.1:5173", "http://[::1]:5173",
                "http://LOCALHOST")) {
            assertThat(KeycloakIdentityProviderAdmin.posOrigin(loopback)).as(loopback).isNotNull();
        }
    }

    @Test
    void anUnsetOriginIsWarnedAboutAtStartup(CapturedOutput output) {
        new KeycloakIdentityProviderAdmin(null, null, "");

        assertThat(output.getOut()).contains("WARN").contains("restaurantpro.pos.origin is not set");
    }

    @Test
    void aSetOriginIsNotWarnedAbout(CapturedOutput output) {
        new KeycloakIdentityProviderAdmin(null, null, "https://pos.restaurantpro.test");

        assertThat(output.getOut()).doesNotContain("restaurantpro.pos.origin is not set");
    }
}
