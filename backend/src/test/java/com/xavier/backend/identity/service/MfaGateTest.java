package com.xavier.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.xavier.backend.access.Actor;
import com.xavier.backend.identity.mfa.MfaMode;
import com.xavier.backend.identity.mfa.MfaProperties;
import com.xavier.backend.identity.model.MfaFactorStatus;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.MfaState;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.MfaFactorRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The login gate with mode OFF reads nothing at all: R4 logins make no extra query (S56 review #3). */
class MfaGateTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC);

    final MfaFactorRepository factors = mock(MfaFactorRepository.class);
    final EnrolmentCodes enrolmentCodes = mock(EnrolmentCodes.class);
    final UserAccount account = UserAccount.open("ana", "{bcrypt}$2a$x", null, Set.of(), CLOCK.instant());
    final SessionView full = SessionView.account(Actor.account(UUID.randomUUID(), Set.of("PERM_X")), "ana", false);

    @Test
    void modeOffAsksTheDatabaseNothingAtLoginOrAfterAPasswordChange() {
        MfaGate gate = gate(MfaMode.OFF);

        assertThat(gate.atLogin(account, full, true, null)).isEqualTo(full);
        assertThat(gate.atLogin(account, full, true, "ABCD-EFGH-IJKL")).isEqualTo(full);
        assertThat(gate.afterPasswordChange(account, full, true, null)).isEqualTo(full);

        verifyNoInteractions(factors, enrolmentCodes);
    }

    @Test
    void anEnforcedModeLooksTheFactorUp() {
        MfaGate gate = gate(MfaMode.ALL_BACKOFFICE);

        assertThat(gate.atLogin(account, full, false, null).mfa().state()).isEqualTo(MfaState.ENROLMENT_REQUIRED);
        verify(factors).existsByAccountIdAndStatus(account.getId(), MfaFactorStatus.ACTIVE);
    }

    {
        account.changePassword("{bcrypt}$2a$y", CLOCK.instant()); // past D30: the gate decides now
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", UUID.randomUUID()); // as if saved
    }

    private MfaGate gate(MfaMode mode) {
        return new MfaGate(new MfaPolicy(new MfaProperties(mode, Duration.ofMinutes(15), null)), factors,
                enrolmentCodes, CLOCK);
    }
}
