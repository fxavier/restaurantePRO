package com.xavier.backend.identity.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Passwords and PINs are hashed with BCrypt behind Spring's delegating encoder (D26): hashes carry their
 * algorithm id ({@code {bcrypt}…}), so the algorithm can change later without breaking stored hashes.
 */
@Configuration(proxyBeanMethods = false)
class PasswordEncoderConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
