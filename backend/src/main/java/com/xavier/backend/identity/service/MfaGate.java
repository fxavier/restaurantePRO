package com.xavier.backend.identity.service;

import com.xavier.backend.access.Actor;
import com.xavier.backend.identity.exception.MfaRefusedException;
import com.xavier.backend.identity.exception.MfaRuleException;
import com.xavier.backend.identity.model.MfaFactorStatus;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.MfaSessionView;
import com.xavier.backend.identity.model.dto.MfaState;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.MfaFactorRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What a BackOffice session gets once its password is right (D140, D141, D145): its authorities, or a session in
 * {@code MFA_PENDING} / {@code MFA_ENROLMENT_REQUIRED} that holds none, or a 403 {@code MFA_NOT_ENROLLED} for an
 * account without a factor that may enrol only with an administrator's enrolment code and gave none. A
 * must-change-password session changes its password first (D30) and meets this gate again afterwards.
 */
@Component
class MfaGate {

    private static final Logger log = LoggerFactory.getLogger(MfaGate.class);

    private final MfaPolicy policy;
    private final MfaFactorRepository factors;
    private final EnrolmentCodes enrolmentCodes;
    private final Clock clock;

    MfaGate(MfaPolicy policy, MfaFactorRepository factors, EnrolmentCodes enrolmentCodes, Clock clock) {
        this.policy = policy;
        this.factors = factors;
        this.enrolmentCodes = enrolmentCodes;
        this.clock = clock;
    }

    /**
     * @param full          the session with the account's authorities
     * @param enrolmentCode checked (not used up) when the account may enrol only with one; it is used at enrolment
     * @throws MfaRefusedException 403 {@code MFA_NOT_ENROLLED} without a needed enrolment code
     * @throws MfaRuleException    422 {@code MFA_ENROLMENT_CODE_INVALID} for a code that is not this account's open one
     */
    SessionView atLogin(UserAccount account, SessionView full, boolean remote, String enrolmentCode) {
        MfaPolicy.LoginRequirement requirement = requirement(account, remote);
        if (requirement == MfaPolicy.LoginRequirement.ENROL_WITH_CODE) {
            requireOpenEnrolmentCode(account, enrolmentCode);
        }
        if (account.isMustChangePassword()) {
            return full; // it holds no authority yet; the gate runs again after the password change
        }
        return gated(account, full, requirement);
    }

    /**
     * After a password change: a session that already proved its factor keeps it; otherwise the same rule as a login
     * (an enrolment code, when needed, was checked at that login and is asked again to enrol).
     */
    SessionView afterPasswordChange(UserAccount account, SessionView full, boolean remote, Instant secondFactorAt) {
        if (secondFactorAt != null) {
            return full.withMfa(MfaSessionView.of(MfaState.VERIFIED, secondFactorAt, policy.stepUpMaxAge()));
        }
        return gated(account, full, requirement(account, remote));
    }

    /** Mode OFF needs nothing, so it reads nothing: an R4 login makes no extra query. */
    private MfaPolicy.LoginRequirement requirement(UserAccount account, boolean remote) {
        if (!policy.enforced()) {
            return MfaPolicy.LoginRequirement.NONE;
        }
        return policy.atLogin(remote, account.isSupport(),
                factors.existsByAccountIdAndStatus(account.getId(), MfaFactorStatus.ACTIVE));
    }

    private static SessionView gated(UserAccount account, SessionView full, MfaPolicy.LoginRequirement requirement) {
        return switch (requirement) {
            case NONE -> full;
            case SECOND_FACTOR -> restricted(account, MfaState.PENDING);
            case ENROL, ENROL_WITH_CODE -> restricted(account, MfaState.ENROLMENT_REQUIRED);
        };
    }

    /** No authority at all until the second factor is proved or enrolled. */
    private static SessionView restricted(UserAccount account, MfaState state) {
        return SessionView.account(Actor.account(account.getId(), Set.of()), account.getUsername(),
                account.isMustChangePassword()).withMfa(MfaSessionView.of(state));
    }

    private void requireOpenEnrolmentCode(UserAccount account, String enrolmentCode) {
        if (enrolmentCode == null || enrolmentCode.isBlank()) {
            log.warn("BackOffice login of user {} refused: a second factor is needed and none is enrolled",
                    account.getId());
            throw MfaRefusedException.notEnrolledAtLogin();
        }
        if (enrolmentCodes.open(account.getId(), enrolmentCode, clock.instant()).isEmpty()) {
            log.warn("BackOffice login of user {} refused: the enrolment code is not valid", account.getId());
            throw MfaRuleException.enrolmentCodeInvalid();
        }
    }
}
