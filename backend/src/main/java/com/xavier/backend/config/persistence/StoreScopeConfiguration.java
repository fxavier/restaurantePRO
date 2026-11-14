package com.xavier.backend.config.persistence;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;

/** The store-scope plumbing of ADR 0039: the connection hook (D165) and the scope advisor (D166). */
@Configuration(proxyBeanMethods = false)
public class StoreScopeConfiguration {

    @Bean
    static StoreScopeDataSourcePostProcessor storeScopeDataSourcePostProcessor(ObjectProvider<StoreScopes> scopes,
                                                                               Environment environment) {
        return new StoreScopeDataSourcePostProcessor(scopes, environment);
    }

    /** Infrastructure role: applied by whichever auto-proxy creator the context has (D166). */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static StoreScopeAdvisor storeScopeAdvisor(ObjectProvider<StoreScopes> scopes,
                                               ObjectProvider<StoreScopeViolations> violations) {
        return new StoreScopeAdvisor(scopes, violations);
    }
}
