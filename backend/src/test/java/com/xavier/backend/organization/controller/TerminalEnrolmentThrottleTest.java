package com.xavier.backend.organization.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.config.security.DeviceHttpTest;
import com.xavier.backend.config.security.Devices;
import com.xavier.backend.config.security.FakeDeviceProvider;
import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.identity.IdentityFixtures;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D182 as amended (A86): the one-time enrolment code. Unknown, used, expired and burned codes get one answer (400
 * {@code ENROLMENT_CODE_INVALID}); five failed attempts on one code burn it; more than ten attempts a minute from one
 * source address are 429 {@code ENROLMENT_THROTTLED}, whatever the code. A successful enrolment registers the device's
 * public key on its own client, grants the {@code TERMINAL} membership of the client's service account and consumes the
 * code; the code itself is never stored.
 */
@DeviceHttpTest
class TerminalEnrolmentThrottleTest {

    @Autowired
    MockMvc mvc;

    Devices devices;
    UUID store;

    @BeforeEach
    void aStore() throws Exception {
        devices = new Devices(mvc);
        store = new IdentityFixtures(mvc).store();
    }

    @Test
    void aCodeEnrolsTheTerminalOnceWithItsPublicKey() throws Exception {
        UUID terminal = devices.register(store, "POS");
        String code = devices.code(terminal);
        Map<String, Object> key = Devices.publicJwk();

        devices.enrol(Devices.POS_ENROLMENT, code, key, Devices.randomAddress())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(terminal.toString()))
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.clientId").value("pos-" + terminal));

        assertThat(FakeDeviceProvider.KEYS.get("pos-" + terminal)).contains((String) key.get("x"))
                .doesNotContain("\"d\"");
        assertThat(membershipsOf(FakeDeviceProvider.subjectOf("pos-" + terminal), "TERMINAL")).isEqualTo(1);
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM organization.terminal WHERE id = ? "
                        + "AND status = 'ACTIVE'")
                .param(terminal).query(Long.class).single()).isEqualTo(1);
        devices.enrol(Devices.POS_ENROLMENT, code, Devices.publicJwk(), Devices.randomAddress())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENROLMENT_CODE_INVALID"));
    }

    /**
     * Review 5: the device lost the 200 and retries with the same code and the same key: the same answer, and nothing
     * new (no second client, key or membership). Another key with that code is still refused.
     */
    @Test
    void aRetryWithTheSameKeyAfterTheEnrolmentGetsTheSameAnswer() throws Exception {
        UUID terminal = devices.register(store, "POS");
        String code = devices.code(terminal);
        Map<String, Object> key = Devices.publicJwk();
        String first = devices.enrol(Devices.POS_ENROLMENT, code, key, Devices.randomAddress())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int clientCalls = FakeDeviceProvider.CREATE_CALLS.get("pos-" + terminal).get();

        devices.enrol(Devices.POS_ENROLMENT, code, key, Devices.randomAddress())
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(first));

        assertThat(FakeDeviceProvider.CREATE_CALLS.get("pos-" + terminal).get()).isEqualTo(clientCalls);
        assertThat(membershipsOf(FakeDeviceProvider.subjectOf("pos-" + terminal), "TERMINAL")).isEqualTo(1);
        devices.enrol(Devices.POS_ENROLMENT, code, Devices.publicJwk(), Devices.randomAddress())
                .andExpect(status().isBadRequest());
        assertThat(FakeDeviceProvider.KEYS.get("pos-" + terminal)).contains((String) key.get("x"));
    }

    /** Review 4: refusals and burned codes are counted for alerting (no tenant label, rule 7). */
    @Test
    void refusalsAndBurnedCodesAreCounted() throws Exception {
        UUID terminal = devices.register(store, "POS");
        String code = devices.code(terminal);
        String wrong = code.substring(0, 8) + "0".repeat(code.length() - 8);
        double refused = count("enrolment_refused_total");
        double burned = count("enrolment_code_burned_total");

        for (int attempt = 0; attempt < 5; attempt++) {
            devices.enrol(Devices.POS_ENROLMENT, wrong, Devices.publicJwk(), Devices.randomAddress());
        }

        assertThat(count("enrolment_refused_total")).isEqualTo(refused + 5);
        assertThat(count("enrolment_code_burned_total")).isEqualTo(burned + 1);
        assertThat(meters.find("enrolment_refused_total").counter().getId().getTags()).isEmpty();
    }

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    private double count(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void unknownAndMalformedCodesGetTheSameAnswer() throws Exception {
        for (String code : new String[] {"ABCDEFGH0123456789ABCDEF", "nonsense", "ABCD-EFGH-0123-4567-89AB-CDEF"}) {
            devices.enrol(Devices.POS_ENROLMENT, code, Devices.publicJwk(), Devices.randomAddress())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ENROLMENT_CODE_INVALID"))
                    .andExpect(jsonPath("$.detail").value("The enrolment code is invalid or has expired."));
        }
    }

    @Test
    void fiveFailedAttemptsBurnTheCode() throws Exception {
        UUID terminal = devices.register(store, "POS");
        String code = devices.code(terminal);
        String wrong = code.substring(0, 8) + "0".repeat(code.length() - 8);

        for (int attempt = 0; attempt < 5; attempt++) {
            devices.enrol(Devices.POS_ENROLMENT, wrong, Devices.publicJwk(), Devices.randomAddress())
                    .andExpect(status().isBadRequest());
        }

        devices.enrol(Devices.POS_ENROLMENT, code, Devices.publicJwk(), Devices.randomAddress())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENROLMENT_CODE_INVALID"));
        assertThat(FakeDeviceProvider.KEYS).doesNotContainKey("pos-" + terminal);
    }

    @Test
    void fourFailedAttemptsLeaveTheCodeUsable() throws Exception {
        UUID terminal = devices.register(store, "POS");
        String code = devices.code(terminal);
        String wrong = code.substring(0, 8) + "0".repeat(code.length() - 8);
        for (int attempt = 0; attempt < 4; attempt++) {
            devices.enrol(Devices.POS_ENROLMENT, wrong, Devices.publicJwk(), Devices.randomAddress())
                    .andExpect(status().isBadRequest());
        }

        devices.enrol(Devices.POS_ENROLMENT, code, Devices.publicJwk(), Devices.randomAddress())
                .andExpect(status().isOk());
    }

    @Test
    void theEleventhAttemptInAMinuteFromOneAddressIsThrottled() throws Exception {
        String address = Devices.randomAddress();
        for (int attempt = 0; attempt < 10; attempt++) {
            devices.enrol(Devices.POS_ENROLMENT, "ABCDEFGH0123456789ABCDEF", Devices.publicJwk(), address)
                    .andExpect(status().isBadRequest());
        }
        UUID terminal = devices.register(store, "POS");
        String code = devices.code(terminal);

        devices.enrol(Devices.POS_ENROLMENT, code, Devices.publicJwk(), address)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ENROLMENT_THROTTLED"))
                .andExpect(header().string("Retry-After", "60")); // FE-F12: the throttle's window, in seconds
        devices.enrol(Devices.POS_ENROLMENT, code, Devices.publicJwk(), Devices.randomAddress())
                .andExpect(status().isOk());
    }

    @Test
    void anExpiredCodeIsInvalid() throws Exception {
        UUID terminal = devices.register(store, "POS");
        String code = devices.code(terminal);
        OwnerDatabase.shared().jdbc().sql("UPDATE organization.terminal SET enrollment_code_expires_at = now() "
                + "- interval '1 second' WHERE id = ?").param(terminal).update();

        devices.enrol(Devices.POS_ENROLMENT, code, Devices.publicJwk(), Devices.randomAddress())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENROLMENT_CODE_INVALID"));
    }

    @Test
    void aTerminalCodeDoesNotEnrolABridgeNorTheReverse() throws Exception {
        String terminalCode = devices.code(devices.register(store, "POS"));
        String bridgeCode = devices.code(devices.register(store, "PRINT_BRIDGE"));

        devices.enrol(Devices.BRIDGE_ENROLMENT, terminalCode, Devices.publicJwk(), Devices.randomAddress())
                .andExpect(status().isBadRequest());
        devices.enrol(Devices.POS_ENROLMENT, bridgeCode, Devices.publicJwk(), Devices.randomAddress())
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPrivateKeyIsRefusedAndNeverRegistered() throws Exception {
        UUID terminal = devices.register(store, "POS");
        String code = devices.code(terminal);
        Map<String, Object> withPrivatePart = new java.util.HashMap<>(Devices.publicJwk());
        withPrivatePart.put("d", "c2VjcmV0");

        devices.enrol(Devices.POS_ENROLMENT, code, withPrivatePart, Devices.randomAddress())
                .andExpect(status().isBadRequest());

        assertThat(FakeDeviceProvider.KEYS).doesNotContainKey("pos-" + terminal);
    }

    /** Two devices racing with one code (atomic, D182): one is enrolled, the other refused, one membership. */
    @Test
    void twoDevicesRacingWithOneCodeEnrolOnlyOne() throws Exception {
        UUID terminal = devices.register(store, "POS");
        String code = devices.code(terminal);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> attempt = () -> {
            start.await();
            return devices.enrol(Devices.POS_ENROLMENT, code, Devices.publicJwk(), Devices.randomAddress())
                    .andReturn().getResponse().getStatus();
        };

        java.util.List<Integer> statuses;
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            start.countDown();
            statuses = java.util.List.of(first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(30, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertThat(statuses).containsOnlyOnce(200);
        assertThat(statuses).filteredOn(status -> status != 200).containsExactly(400);
        assertThat(membershipsOf(FakeDeviceProvider.subjectOf("pos-" + terminal), "TERMINAL")).isEqualTo(1);
    }

    private static long membershipsOf(String subject, String kind) {
        return OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM tenancy.tenant_membership WHERE issuer = ? "
                        + "AND subject = ? AND kind = ? AND status = 'ACTIVE'")
                .param(TestJwtFactory.DEVICES_ISSUER).param(subject).param(kind).query(Long.class).single();
    }
}
