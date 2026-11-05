package com.xavier.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.lifecycle.TestcontainersLifecycleApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The shared PostgreSQL outlives any context that imports {@link TestcontainersConfiguration}. Spring's test context
 * cache closes the least recently used context once it holds more than 32; Spring Boot's Testcontainers lifecycle
 * (applied to every context by its initializer) stops each container bean of a closing context. When the shared
 * container was such a bean, one eviction stopped it under every other cached context (the S31 full run: a
 * context's close at 22:43:28, then "Connection refused" for a context cached earlier).
 */
class SharedDatabaseSurvivesContextCloseTest {

    @Test
    void closingAContextLikeAnEvictionLeavesTheSharedDatabaseRunning() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        new TestcontainersLifecycleApplicationContextInitializer().initialize(context);
        context.register(TestcontainersConfiguration.class);
        context.refresh();

        context.close();

        var postgres = TestcontainersConfiguration.postgres();
        assertThat(postgres.isRunning()).isTrue();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); ResultSet one = connection.createStatement().executeQuery("SELECT 1")) {
            assertThat(one.next()).isTrue();
        }
    }
}
