package com.xavier.backend.identity;

import com.xavier.backend.identity.mfa.MfaMode;
import com.xavier.backend.identity.mfa.MfaProperties;
import com.xavier.backend.identity.service.MfaPolicy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The MFA mode is a deployment property; tests of all three modes share one context by switching it here. It starts
 * {@code OFF} so fixtures that act as an unenrolled administrator work; a test sets its mode and {@link #reset()}s it
 * in {@code @AfterEach}.
 */
public class SwitchableMfaPolicy extends MfaPolicy {

    private volatile MfaMode mode = MfaMode.OFF;

    public SwitchableMfaPolicy(MfaProperties properties) {
        super(properties);
    }

    public void set(MfaMode newMode) {
        mode = newMode;
    }

    public void reset() {
        mode = MfaMode.OFF;
    }

    @Override
    public MfaMode mode() {
        return mode;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        SwitchableMfaPolicy switchableMfaPolicy(MfaProperties properties) {
            return new SwitchableMfaPolicy(properties);
        }
    }
}
