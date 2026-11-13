package com.xavier.backend.identity.service;

import com.xavier.backend.identity.mfa.MfaMode;
import com.xavier.backend.identity.mfa.MfaProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The installation's MFA policy (D140, D141, D145): what a password login asks for, whether step-up (D142) is
 * enforced, and how long a proved second factor stays fresh. The mode is a deployment property, so an administrator
 * session cannot switch it off through the API.
 */
@Component
@EnableConfigurationProperties({MfaProperties.class, LanProperties.class})
public class MfaPolicy {

    /** What a password login needs before the session gets its authorities. */
    public enum LoginRequirement {
        /** Nothing more: the session starts with its authorities (a later sensitive call may still step up). */
        NONE,
        /** The account has a factor: the session starts {@code MFA_PENDING} until {@code /auth/mfa/verify}. */
        SECOND_FACTOR,
        /** No factor, on the LAN: the session starts {@code MFA_ENROLMENT_REQUIRED} and enrols right away. */
        ENROL,
        /** No factor, remote or a support account: only with an administrator's enrolment code (D143, D145). */
        ENROL_WITH_CODE
    }

    private final MfaProperties properties;

    public MfaPolicy(MfaProperties properties) {
        this.properties = properties;
    }

    public MfaMode mode() {
        return properties.mode();
    }

    public Duration stepUpMaxAge() {
        return properties.stepUpMaxAge();
    }

    /** Step-up for sensitive permissions applies (every mode but {@code OFF}). */
    public boolean enforced() {
        return mode() != MfaMode.OFF;
    }

    /**
     * {@code SENSITIVE}: a second factor when the request is remote or the account is a support account (D145);
     * {@code ALL_BACKOFFICE}: always; {@code OFF}: never.
     */
    public LoginRequirement atLogin(boolean remote, boolean support, boolean enrolled) {
        boolean needed = switch (mode()) {
            case OFF -> false;
            case SENSITIVE -> remote || support;
            case ALL_BACKOFFICE -> true;
        };
        if (!needed) {
            return LoginRequirement.NONE;
        }
        if (enrolled) {
            return LoginRequirement.SECOND_FACTOR;
        }
        return enrolmentNeedsCode(remote, support) ? LoginRequirement.ENROL_WITH_CODE : LoginRequirement.ENROL;
    }

    /** A stolen password cannot enrol the attacker's phone from outside; support access is remote by nature. */
    static boolean enrolmentNeedsCode(boolean remote, boolean support) {
        return remote || support;
    }
}
