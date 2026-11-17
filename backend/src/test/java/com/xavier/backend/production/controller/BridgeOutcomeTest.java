package com.xavier.backend.production.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.config.security.DeviceHttpTest;
import com.xavier.backend.config.security.Devices;
import com.xavier.backend.config.security.Devices.Device;
import com.xavier.backend.identity.IdentityFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D187 / D57 / D50 §7.2: the bridge reports what its send achieved, {@code POST /api/v1/bridge/jobs/{id}/outcome}, and
 * the job moves as the in-backend sender's outcome moved it: ACCEPTED → SENT; nothing written (NOT_CONNECTED, a
 * status-probe refusal) → RETRY_WAIT; INTERRUPTED_AFTER_WRITE → UNKNOWN (never resent). An outcome counts once (a
 * repeat, or one for a job no longer PROCESSING, changes nothing), and a bridge reports only its own store's jobs.
 */
@DeviceHttpTest
class BridgeOutcomeTest {

    @Autowired
    MockMvc mvc;

    BridgeJobs jobs;
    UUID store;
    Device bridge;
    UUID printer;

    @BeforeEach
    void aStoreWithItsBridgeAndPrinter() throws Exception {
        jobs = new BridgeJobs(mvc);
        store = new IdentityFixtures(mvc).store();
        bridge = new Devices(mvc).enrolledBridge(store);
        printer = jobs.printer(store, "192.168.1.60", 9100);
    }

    @Test
    void anAcceptedSendIsSentAndARepeatChangesNothing() throws Exception {
        UUID job = claimedJob();

        jobs.outcome(bridge.bearer(), job, "ACCEPTED").andExpect(status().isNoContent());
        Object sentAt = BridgeJobs.job(job).get("sent_at");
        jobs.outcome(bridge.bearer(), job, "NOT_CONNECTED").andExpect(status().isNoContent());

        assertThat(BridgeJobs.job(job)).containsEntry("status", "SENT").containsEntry("attempts", 1)
                .containsEntry("sent_at", sentAt);
    }

    @Test
    void aSendThatWroteNothingWaitsToRetry() throws Exception {
        UUID job = claimedJob();

        jobs.outcome(bridge.bearer(), job, "NOT_CONNECTED").andExpect(status().isNoContent());

        assertThat(BridgeJobs.job(job)).containsEntry("status", "RETRY_WAIT")
                .containsEntry("last_error", "NOT_CONNECTED");
    }

    @Test
    void anInterruptedSendIsUnknown() throws Exception {
        UUID job = claimedJob();

        jobs.outcome(bridge.bearer(), job, "INTERRUPTED_AFTER_WRITE").andExpect(status().isNoContent());

        assertThat(BridgeJobs.job(job)).containsEntry("status", "UNKNOWN");
    }

    /** Review 6: only the bridge that claimed a job reports it; a job nobody claimed is nobody's to report. */
    @Test
    void anOutcomeForAJobNeverClaimedChangesNothing() throws Exception {
        UUID job = jobs.queuedJob(printer);

        jobs.outcome(bridge.bearer(), job, "ACCEPTED").andExpect(status().isNotFound());

        assertThat(BridgeJobs.job(job)).containsEntry("status", "QUEUED").containsEntry("attempts", 0);
    }

    /** Review 6: another bridge of the same store cannot report a job this bridge claimed (404, as another store). */
    @Test
    void anotherBridgeOfTheSameStoreCannotReportTheJob() throws Exception {
        Device second = new Devices(mvc).enrolledBridge(store);
        UUID job = claimedJob();

        jobs.outcome(second.bearer(), job, "ACCEPTED").andExpect(status().isNotFound());

        assertThat(BridgeJobs.job(job)).containsEntry("status", "PROCESSING");
        jobs.outcome(bridge.bearer(), job, "ACCEPTED").andExpect(status().isNoContent());
        assertThat(BridgeJobs.job(job)).containsEntry("status", "SENT");
    }

    @Test
    void aBridgeCannotReportAnotherStoresJob() throws Exception {
        UUID otherStore = new IdentityFixtures(mvc).store();
        Device otherBridge = new Devices(mvc).enrolledBridge(otherStore);
        UUID otherJob = jobs.queuedJob(jobs.printer(otherStore, "192.168.2.60", 9100));
        jobs.claimed(otherBridge.bearer());

        jobs.outcome(bridge.bearer(), otherJob, "ACCEPTED").andExpect(status().isNotFound());
        jobs.outcome(BridgeJobs.bridgeOfAnotherTenant(com.xavier.backend.config.security.Members.activeTenant()),
                otherJob, "ACCEPTED").andExpect(status().isNotFound());

        assertThat(BridgeJobs.job(otherJob)).containsEntry("status", "PROCESSING");
    }

    @Test
    void anUnknownOutcomeIsABadRequest() throws Exception {
        UUID job = claimedJob();

        mvc.perform(post(BridgeJobs.JOBS + "/" + job + "/outcome").with(bridge.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\": \"PRINTED_PROBABLY\"}"))
                .andExpect(status().isBadRequest());
        assertThat(BridgeJobs.job(job)).containsEntry("status", "PROCESSING");
    }

    private UUID claimedJob() throws Exception {
        UUID job = jobs.queuedJob(printer);
        assertThat(jobs.claimed(bridge.bearer()).valueStream().map(j -> j.get("id").asString()))
                .contains(job.toString());
        return job;
    }
}
