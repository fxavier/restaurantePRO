package com.xavier.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Stored sessions survive upgrades safely (D38). A session attribute written by an older release can no longer be
 * read when a stored type changed shape (the R1 {@code Actor} had no {@code kind}). Spring Session would then fail
 * every request carrying that cookie with a 500; here such an attribute reads as absent instead, so the security
 * context is missing, the request is unauthenticated (401) and the user logs in again.
 */
@Configuration(proxyBeanMethods = false)
class SessionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> outdatedSessionAttributesReadAsAbsent() {
        return repository -> repository.setConversionService(tolerantConversionService());
    }

    private static GenericConversionService tolerantConversionService() {
        ClassLoader classLoader = SessionConfig.class.getClassLoader();
        DeserializingConverter deserializer = new DeserializingConverter(classLoader);
        GenericConversionService conversion = new GenericConversionService();
        conversion.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversion.addConverter(byte[].class, Object.class, (Converter<byte[], Object>) bytes -> {
            try {
                return deserializer.convert(bytes);
            } catch (RuntimeException e) {
                // no attribute content in the log: it may hold the principal's authorities
                log.warn("Ignoring a stored session attribute that this release cannot read ({}); "
                        + "the session is treated as logged out", e.getClass().getSimpleName());
                return null;
            }
        });
        return conversion;
    }
}
