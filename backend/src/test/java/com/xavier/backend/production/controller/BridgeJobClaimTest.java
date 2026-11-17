package com.xavier.backend.production.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.config.security.DeviceHttpTest;
import com.xavier.backend.config.security.Devices;
import com.xavier.backend.config.security.Devices.Device;
import com.xavier.backend.config.security.Members;
import com.xavier.backend.identity.IdentityFixtures;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D187 / A89: the backend never connects to a printer; a print bridge on the store's LAN claims its store's jobs over
 * an outbound request ({@code GET /api/v1/bridge/jobs}), each with a lease ({@code PROCESSING}, the D50 lease), and
 * gets what it needs to send it (the printer's LAN address and the rendered ESC/POS bytes). A bridge of store S never
 * claims a job of another store of its tenant, nor of another tenant; a claimed job is not claimed twice.
 */
@DeviceHttpTest
class BridgeJobClaimTest {

    @Autowired
    MockMvc mvc;

    BridgeJobs jobs;
    Devices devices;
    UUID store;
    Device bridge;

    @BeforeEach
    void aStoreWithItsBridge() throws Exception {
        jobs = new BridgeJobs(mvc);
        devices = new Devices(mvc);
        store = new IdentityFixtures(mvc).store();
        bridge = devices.enrolledBridge(store);
    }

    @Test
    void theBridgeClaimsItsStoresJobWithTheLanAddressAndTheBytes() throws Exception {
        UUID printer = jobs.printer(store, "192.168.1.50", 9100);
        UUID job = jobs.queuedJob(printer);

        JsonNode claimed = jobs.claimed(bridge.bearer());

        assertThat(claimed.valueStream().map(j -> j.get("id").asString())).containsExactly(job.toString());
        JsonNode first = claimed.get(0);
        assertThat(first.get("host").asString()).isEqualTo("192.168.1.50");
        assertThat(first.get("port").asInt()).isEqualTo(9100);
        assertThat(Base64.getDecoder().decode(first.get("payload").asString())).isNotEmpty();
        assertThat(first.has("probeStatus")).isTrue();
        assertThat(BridgeJobs.job(job)).containsEntry("status", "PROCESSING").containsEntry("attempts", 1);
    }

    @Test
    void aClaimedJobIsNotClaimedAgain() throws Exception {
        UUID job = jobs.queuedJob(jobs.printer(store, "192.168.1.51", 9100));
        jobs.claimed(bridge.bearer());

        assertThat(jobs.claimed(bridge.bearer()).valueStream().map(j -> j.get("id").asString()))
                .doesNotContain(job.toString());
    }

    @Test
    void aBridgeNeverClaimsAnotherStoresJob() throws Exception {
        UUID otherStore = new IdentityFixtures(mvc).store();
        UUID otherJob = jobs.queuedJob(jobs.printer(otherStore, "192.168.2.50", 9100));

        assertThat(jobs.claimed(bridge.bearer()).valueStream().map(j -> j.get("id").asString()))
                .doesNotContain(otherJob.toString());
        assertThat(BridgeJobs.job(otherJob)).containsEntry("status", "QUEUED").containsEntry("attempts", 0);
    }

    @Test
    void aBridgeOfAnotherTenantNeverClaimsThisTenantsJob() throws Exception {
        UUID job = jobs.queuedJob(jobs.printer(store, "192.168.1.52", 9100));

        JsonNode foreign = jobs.claimed(BridgeJobs.bridgeOfAnotherTenant(Members.activeTenant()));

        assertThat(foreign.valueStream().map(j -> j.get("id").asString())).doesNotContain(job.toString());
        assertThat(BridgeJobs.job(job)).containsEntry("status", "QUEUED");
    }

    @Test
    void onlyAnEnrolledBridgeClaims() throws Exception {
        Device terminal = devices.enrolledTerminal(store);
        jobs.claim(terminal.bearer()).andExpect(status().isUnauthorized());
        jobs.claim(com.xavier.backend.config.security.TestJwtFactory.account("kc-" + UUID.randomUUID()).bearer())
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/backoffice/terminals/" + bridge.id() + "/revoke").with(admin()))
                .andExpect(status().isOk());
        jobs.claim(bridge.bearer()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    /**
     * D187: the central backend cannot reach a restaurant's LAN, so nothing in it opens a connection to a printer: the
     * PrintWorker only dispatches (expires lost leases), the bridge sends.
     */
    @Test
    void theBackendNeverConnectsToAPrinter() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.xavier.backend..")
                .should().dependOnClassesThat().areAssignableTo(java.net.Socket.class)
                .because("printing goes through the store's print bridge (D187)")
                .check(new com.tngtech.archunit.core.importer.ClassFileImporter()
                        .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.xavier.backend"));
    }
}
