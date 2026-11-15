package com.xavier.backend.config.persistence;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.ActiveTenants;
import com.xavier.backend.shared.tenancy.JobLeases;
import com.xavier.backend.shared.tenancy.TenantScopes;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;

/**
 * The tenant and store-scope plumbing of ADR 0040 / ADR 0039: the transaction manager that binds both at every begin
 * (D178), the transactions of declared repository query methods, and the advisor of actor-less entry points (D166,
 * D185).
 */
@Configuration(proxyBeanMethods = false)
public class StoreScopeConfiguration {

    /**
     * The only {@code PlatformTransactionManager} (Boot's JPA one backs off, {@code @ConditionalOnMissingBean}); it
     * applies Boot's {@link TransactionManagerCustomizers} itself and finds the EntityManagerFactory as Boot's does.
     */
    @Bean
    static TenantBindingTransactionManager transactionManager(ObjectProvider<TenantScopes> tenants,
                                                              ObjectProvider<StoreScopes> stores,
                                                              Environment environment,
                                                              ObjectProvider<TransactionManagerCustomizers>
                                                                      customizers) {
        TenantBindingTransactionManager transactionManager = new TenantBindingTransactionManager(tenants, stores,
                StoreScopeEnforcement.of(environment));
        customizers.ifAvailable(each -> each.customize(transactionManager));
        return transactionManager;
    }

    /** Declared repository query methods begin a bound transaction when they run outside one (D178). */
    @Bean
    static RepositoryQueryTransactions repositoryQueryTransactions() {
        return new RepositoryQueryTransactions();
    }

    /** Hand-written {@code @Repository} classes run in a bound transaction when called outside one (D178). */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static RepositoryClassTransactions repositoryClassTransactions() {
        return new RepositoryClassTransactions();
    }

    /** Infrastructure role: applied by whichever auto-proxy creator the context has (D166). */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static StoreScopeAdvisor storeScopeAdvisor(ObjectProvider<StoreScopes> scopes, ObjectProvider<TenantScopes> tenants,
                                               ObjectProvider<ActiveTenants> activeTenants,
                                               ObjectProvider<JobLeases> leases,
                                               ObjectProvider<StoreScopeViolations> violations,
                                               ObjectProvider<MeterRegistry> meters) {
        return new StoreScopeAdvisor(scopes, tenants, activeTenants, leases, violations, meters);
    }
}
