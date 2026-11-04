package com.xavier.backend.fiscal.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.FiscalModuleTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;

/** A13 / D54: the health endpoint reports the fiscal component NON_CERTIFIED for the internal adapter. */
@FiscalModuleTest
class FiscalHealthTest {

    @Autowired
    HealthEndpoint health;

    @Test
    void theHealthEndpointReportsTheInternalAdapterAsNonCertified() {
        IndicatedHealthDescriptor fiscal = (IndicatedHealthDescriptor) health.healthForPath("fiscal");

        assertThat(fiscal).isNotNull();
        assertThat(fiscal.getStatus().getCode()).isEqualTo("NON_CERTIFIED");
        assertThat(fiscal.getDetails()).containsEntry("adapter", "internal").containsEntry("certified", false);
    }

    @Test
    void aNonCertifiedAdapterDoesNotTakeTheApplicationDown() {
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
    }
}
