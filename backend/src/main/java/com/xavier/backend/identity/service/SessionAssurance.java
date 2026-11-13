package com.xavier.backend.identity.service;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.AssuranceState;
import com.xavier.backend.access.AuthenticationAssurance;
import com.xavier.backend.identity.model.MfaFactorStatus;
import com.xavier.backend.identity.repository.MfaFactorRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Identity's answer to {@code AccessPolicy}'s step-up question (D142): {@code enforced} is the installation mode
 * (not {@code OFF}) for every actor kind, so a POS session reaching a sensitive permission outside the POS gate is
 * refused ({@code MFA_ACCOUNT_REQUIRED}); {@code enrolled} is whether the account has an ACTIVE factor; and
 * {@code secondFactorAt} is the session's {@code MFA_SECOND_FACTOR_AT}. With mode {@code OFF} it answers
 * {@link AssuranceState#NOT_ENFORCED} without reading anything, so R4 behaviour is unchanged. Asked at most once per
 * request ({@code AccessPolicy} caches it), and only for a sensitive permission.
 */
@Service
public class SessionAssurance implements AuthenticationAssurance {

    private final MfaPolicy policy;
    private final MfaFactorRepository factors;

    SessionAssurance(MfaPolicy policy, MfaFactorRepository factors) {
        this.policy = policy;
        this.factors = factors;
    }

    @Override
    public AssuranceState current() {
        if (!policy.enforced()) {
            return AssuranceState.NOT_ENFORCED;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Actor actor)
                || actor.kind() != ActorKind.ACCOUNT) {
            return new AssuranceState(true, false, null, policy.stepUpMaxAge());
        }
        boolean enrolled = factors.existsByAccountIdAndStatus(actor.userId(), MfaFactorStatus.ACTIVE);
        return new AssuranceState(true, enrolled,
                MfaSessionAttributes.current().map(MfaSessionAttributes::secondFactorAt).orElse(null),
                policy.stepUpMaxAge());
    }
}
