package com.xavier.backend;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * FE-F28, test-only (registered in {@code META-INF/spring/...AutoConfiguration.imports} of the test resources): every
 * servlet context of the test JVM checks its responses against their {@code @Nullable} annotations.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class NullMarkedResponsesTestAutoConfiguration {

    @Bean
    NullMarkedResponseCheck nullMarkedResponseCheck(ObjectProvider<JsonMapper> json) {
        return new NullMarkedResponseCheck(json);
    }
}
