package com.xavier.backend.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * A resolve waits for the provider inside a D63 step, so the provider timeout must stay below the idempotency step
 * lease (PT30S): otherwise a retry could take over the claim while the first request still waits.
 */
class ProviderTimeoutGuardTest {

    static final Duration STEP_LEASE = Duration.ofSeconds(30);

    @Test
    void theDefaultProviderTimeoutIsBelowTheDefaultStepLease() {
        SettlementProperties defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("restaurantpro.settlement", SettlementProperties.class);

        assertThat(defaults.providerTimeout()).isEqualTo(Duration.ofSeconds(10)).isLessThan(STEP_LEASE);
        assertThat(ProviderCalls.requireBelowStepLease(defaults.providerTimeout(), STEP_LEASE))
                .isEqualTo(defaults.providerTimeout());
    }

    @Test
    void startupFailsWhenTheProviderTimeoutReachesTheStepLease() {
        assertThatThrownBy(() -> ProviderCalls.requireBelowStepLease(STEP_LEASE, STEP_LEASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-lease");
        assertThatThrownBy(() -> ProviderCalls.requireBelowStepLease(Duration.ZERO, STEP_LEASE))
                .isInstanceOf(IllegalStateException.class);
    }
}
