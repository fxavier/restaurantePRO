package com.xavier.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.identity.mfa.MfaMode;
import com.xavier.backend.identity.mfa.MfaProperties;
import com.xavier.backend.identity.service.MfaPolicy.LoginRequirement;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What a password login asks for (D140, D141, D145): the whole matrix of mode × LAN/remote × support × enrolled.
 * The HTTP flows of A58 are in {@code MfaRemotePolicyTest}.
 */
class MfaPolicyTest {

    @ParameterizedTest(name = "{0} remote={1} support={2} enrolled={3} -> {4}")
    @CsvSource({
            // OFF: R4 behaviour whatever the request
            "OFF, false, false, false, NONE",
            "OFF, false, false, true, NONE",
            "OFF, true, false, false, NONE",
            "OFF, true, false, true, NONE",
            "OFF, false, true, false, NONE",
            "OFF, true, true, true, NONE",
            // SENSITIVE: a second factor when remote, or for a support account wherever it is
            "SENSITIVE, false, false, false, NONE",
            "SENSITIVE, false, false, true, NONE",
            "SENSITIVE, true, false, true, SECOND_FACTOR",
            "SENSITIVE, true, false, false, ENROL_WITH_CODE",
            "SENSITIVE, false, true, true, SECOND_FACTOR",
            "SENSITIVE, false, true, false, ENROL_WITH_CODE",
            "SENSITIVE, true, true, true, SECOND_FACTOR",
            "SENSITIVE, true, true, false, ENROL_WITH_CODE",
            // ALL_BACKOFFICE: every login; enrolling on the LAN needs no code, remotely or as support it does
            "ALL_BACKOFFICE, false, false, true, SECOND_FACTOR",
            "ALL_BACKOFFICE, false, false, false, ENROL",
            "ALL_BACKOFFICE, true, false, true, SECOND_FACTOR",
            "ALL_BACKOFFICE, true, false, false, ENROL_WITH_CODE",
            "ALL_BACKOFFICE, false, true, true, SECOND_FACTOR",
            "ALL_BACKOFFICE, false, true, false, ENROL_WITH_CODE"})
    void aLoginAsksFor(MfaMode mode, boolean remote, boolean support, boolean enrolled, LoginRequirement expected) {
        MfaPolicy policy = new MfaPolicy(new MfaProperties(mode, Duration.ofMinutes(15), null));

        assertThat(policy.atLogin(remote, support, enrolled)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"OFF, false", "SENSITIVE, true", "ALL_BACKOFFICE, true"})
    void stepUpIsEnforcedUnlessOff(MfaMode mode, boolean enforced) {
        assertThat(new MfaPolicy(new MfaProperties(mode, Duration.ofMinutes(15), null)).enforced())
                .isEqualTo(enforced);
    }

    @ParameterizedTest
    @CsvSource({"false, false, false", "true, false, true", "false, true, true", "true, true, true"})
    void enrollingNeedsACodeRemotelyOrForASupportAccount(boolean remote, boolean support, boolean needsCode) {
        assertThat(MfaPolicy.enrolmentNeedsCode(remote, support)).isEqualTo(needsCode);
    }
}
