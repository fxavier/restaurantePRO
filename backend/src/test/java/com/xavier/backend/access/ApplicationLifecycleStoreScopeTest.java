package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * D171: an application started or closed by a test body (a simulated restart, {@code new SpringApplicationBuilder(...)
 * .run()}) runs its startup and shutdown code with production semantics, although it runs on the marked JUnit thread:
 * runners, the ready event, the closed event and bean destruction see no fixture fallback. The test body around them
 * keeps it.
 */
class ApplicationLifecycleStoreScopeTest {

    static final StoreScopes SCOPES = StoreScopeTestSupport.scopes();
    static final Map<String, String> SEEN = new ConcurrentHashMap<>();

    @BeforeEach
    void forget() {
        SEEN.clear();
    }

    @Test
    void anApplicationStartedAndClosedInATestBodyHasProductionSemantics() {
        ConfigurableApplicationContext application = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE).run();
        String afterRun = SCOPES.current().origin();
        application.close();

        assertThat(SEEN).containsOnly(Map.entry("runner", "none"), Map.entry("ready", "none"),
                Map.entry("closed", "none"), Map.entry("destroy", "none"));
        assertThat(afterRun).as("the test body after run()").isEqualTo("test-fixture");
        assertThat(SCOPES.current().origin()).as("the test body after close()").isEqualTo("test-fixture");
    }

    @Test
    void aFailedStartRestoresTheTestBodysFallback() {
        try {
            new SpringApplicationBuilder(Failing.class).web(WebApplicationType.NONE).run();
        } catch (IllegalStateException expected) {
            // the start failed, as intended
        }

        assertThat(SCOPES.current().origin()).isEqualTo("test-fixture");
    }

    @Test
    void aRefreshThatFailsRestoresTheTestBodysFallback() {
        try {
            new SpringApplicationBuilder(Unbuildable.class).web(WebApplicationType.NONE).run();
        } catch (RuntimeException expected) {
            // the refresh failed, as intended: beans are destroyed with no closed event
        }

        assertThat(SCOPES.current().origin()).isEqualTo("test-fixture");
    }

    @Configuration(proxyBeanMethods = false)
    static class Unbuildable {

        @Bean
        DisposableBean built() {
            return () -> SEEN.put("destroy", SCOPES.current().origin());
        }

        @Bean
        Object unbuildable(DisposableBean built) {
            throw new IllegalStateException("refresh refused");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Application {

        @Bean
        ApplicationRunner runner() {
            return args -> SEEN.put("runner", SCOPES.current().origin());
        }

        @Bean
        DisposableBean destroyed() {
            return () -> SEEN.put("destroy", SCOPES.current().origin());
        }

        @EventListener
        void ready(ApplicationReadyEvent event) {
            SEEN.put("ready", SCOPES.current().origin());
        }

        @EventListener
        void closed(ContextClosedEvent event) {
            SEEN.put("closed", SCOPES.current().origin());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Failing {

        @Bean
        ApplicationRunner failing() {
            return args -> {
                throw new IllegalStateException("start refused");
            };
        }
    }
}
