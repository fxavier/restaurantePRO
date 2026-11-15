package com.xavier.backend;

import com.xavier.backend.access.FixtureFreeRequests;
import com.xavier.backend.access.StoreScopeFallback;
import com.xavier.backend.access.StoreScopeTestSupport;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.config.persistence.StoreScopeConfiguration;
import com.xavier.backend.shared.tenancy.TenantFallback;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * D171, test-only (registered in {@code META-INF/spring/...AutoConfiguration.imports} of the test resources): every
 * application context of the test JVM, module tests, full contexts and e2e installations alike, runs with the store
 * scope of production plus the fixture fallback for the JUnit thread.
 *
 * <p><b>Binding and advisor in module tests.</b> {@code @ApplicationModuleTest} removes, after configuration parsing,
 * the bean definitions whose type belongs to a module the test does not boot. The tenant-binding transaction manager
 * (D178) and the advisor (D166, D185) are beans of {@code config}, which no module test boots, so an
 * {@code @Import(StoreScopeConfiguration)} was dropped too: no transaction would carry a tenant or a scope, and jobs
 * and listeners would run unbound. {@link StoreScopePlumbing} runs after that filter (bean factory post-processors run
 * after every registry post-processor) and, when no {@link StoreScopeConfiguration} definition is left, registers the
 * production factory methods again under their production names; the transaction manager replaces Boot's JPA one
 * (registered meanwhile under the same name, since the production one was gone when Boot's condition ran). In a full
 * context it does nothing, so there is exactly one transaction manager and one advisor. This class lives in the root
 * package, which belongs to no module, so the filter keeps it.
 *
 * <p><b>Fixture fallback.</b> SQL in a test body on the JUnit thread with neither an actor nor an explicit scope sees
 * every store ({@link StoreScopeTestSupport}) of the bootstrap tenant ({@link TenantTestSupport}); a request dispatched
 * by MockMvc does not see every store ({@link FixtureFreeRequests}). In a context without the {@code access} module
 * there is no {@code StoreScopes}, so nothing is scoped (fail closed).
 */
@AutoConfiguration
public final class StoreScopeTestAutoConfiguration {

    @Bean
    static BeanFactoryPostProcessor storeScopePlumbing() {
        return new StoreScopePlumbing();
    }

    /** Fixture SQL on the JUnit thread sees every store; any other thread, and a strict test, gets production's. */
    @Bean
    @Primary
    StoreScopeFallback testFixtureStoreScopeFallback() {
        return StoreScopeTestSupport.FIXTURE_FALLBACK;
    }

    /** Fixture SQL on the JUnit thread runs in the bootstrap tenant; any other thread, and a strict test, unbound. */
    @Bean
    @Primary
    TenantFallback testFixtureTenantFallback() {
        return TenantTestSupport.FALLBACK;
    }

    /** A request dispatched by MockMvc on the JUnit thread does not inherit the fixture fallback. */
    @Bean
    FixtureFreeRequests fixtureFreeRequests() {
        return new FixtureFreeRequests();
    }

    /** The fixture mechanics for autocommit SQL in test bodies ({@link FixtureCheckouts}), unless switched off. */
    @Bean
    static BeanPostProcessor fixtureCheckouts(ObjectProvider<TenantScopes> tenants, ObjectProvider<StoreScopes> stores,
                                              Environment environment) {
        boolean enabled = environment.getProperty(FixtureCheckouts.PROPERTY, Boolean.class, true);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!enabled || !(bean instanceof DataSource dataSource) || bean instanceof FixtureCheckouts) {
                    return bean;
                }
                return new FixtureCheckouts(dataSource, tenants, stores);
            }
        };
    }

    /** Restores {@link StoreScopeConfiguration}'s two beans where a module test dropped them. */
    static final class StoreScopePlumbing implements BeanFactoryPostProcessor {

        static final String TRANSACTION_MANAGER = "transactionManager";
        static final String QUERY_TRANSACTIONS = "repositoryQueryTransactions";
        static final String CLASS_TRANSACTIONS = "repositoryClassTransactions";
        static final String ADVISOR = "storeScopeAdvisor";

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
            if (!(factory instanceof BeanDefinitionRegistry registry)
                    || factory.getBeanNamesForType(StoreScopeConfiguration.class, true, false).length > 0) {
                return;
            }
            if (registry.containsBeanDefinition(TRANSACTION_MANAGER)) {
                registry.removeBeanDefinition(TRANSACTION_MANAGER); // Boot's JPA one: it would bind nothing
            }
            registry.registerBeanDefinition(TRANSACTION_MANAGER,
                    productionFactoryMethod(TRANSACTION_MANAGER, BeanDefinition.ROLE_APPLICATION));
            registry.registerBeanDefinition(QUERY_TRANSACTIONS,
                    productionFactoryMethod(QUERY_TRANSACTIONS, BeanDefinition.ROLE_INFRASTRUCTURE));
            registry.registerBeanDefinition(CLASS_TRANSACTIONS,
                    productionFactoryMethod(CLASS_TRANSACTIONS, BeanDefinition.ROLE_INFRASTRUCTURE));
            registry.registerBeanDefinition(ADVISOR,
                    productionFactoryMethod(ADVISOR, BeanDefinition.ROLE_INFRASTRUCTURE));
        }

        /** As {@code @Bean} registers it: the static factory method, its parameters autowired. */
        private static RootBeanDefinition productionFactoryMethod(String name, int role) {
            RootBeanDefinition definition = new RootBeanDefinition(StoreScopeConfiguration.class);
            definition.setFactoryMethodName(name);
            definition.setAutowireMode(AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
            definition.setRole(role);
            return definition;
        }
    }
}
