package com.xavier.backend.config.persistence;

import com.xavier.backend.access.StoreScopes;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Wraps every {@link DataSource} bean in a {@link StoreScopedDataSource} (D165), so the JPA EntityManagerFactory,
 * {@code JdbcTemplate}, {@code JdbcClient}, Spring Session JDBC and the event publication registry all receive the
 * hook. Flyway connects on its own (as the owner) and is not wrapped. Without a {@link StoreScopes} bean (a module test
 * that does not include {@code access}) nothing is wrapped. Ordered first, so any other decorator wraps the hook.
 */
class StoreScopeDataSourcePostProcessor implements BeanPostProcessor, Ordered {

    private final ObjectProvider<StoreScopes> scopes;
    private final Environment environment;

    StoreScopeDataSourcePostProcessor(ObjectProvider<StoreScopes> scopes, Environment environment) {
        this.scopes = scopes;
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource dataSource) || bean instanceof StoreScopedDataSource) {
            return bean;
        }
        StoreScopes available = scopes.getIfAvailable();
        if (available == null) {
            return bean;
        }
        return new StoreScopedDataSource(dataSource, available, StoreScopeEnforcement.of(environment));
    }
}
