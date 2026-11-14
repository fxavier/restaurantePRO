package com.xavier.backend.config.persistence;

import com.xavier.backend.access.StoreScopes;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** The store-scope plumbing of ADR 0039: the connection hook (D165). */
@Configuration(proxyBeanMethods = false)
public class StoreScopeConfiguration {

    @Bean
    static StoreScopeDataSourcePostProcessor storeScopeDataSourcePostProcessor(ObjectProvider<StoreScopes> scopes,
                                                                               Environment environment) {
        return new StoreScopeDataSourcePostProcessor(scopes, environment);
    }
}
