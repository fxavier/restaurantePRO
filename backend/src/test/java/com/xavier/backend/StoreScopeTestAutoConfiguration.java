package com.xavier.backend;

import com.xavier.backend.access.FixtureFreeRequests;
import com.xavier.backend.access.StoreScopeFallback;
import com.xavier.backend.access.StoreScopeTestSupport;
import com.xavier.backend.config.persistence.StoreScopeConfiguration;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * D171, test-only (registered in {@code META-INF/spring/...AutoConfiguration.imports} of the test resources): every
 * application context of the test JVM, module tests, full contexts and e2e installations alike, runs with the store
 * scope of production plus the fixture fallback for the JUnit thread.
 *
 * <p><b>Hook and advisor in module tests.</b> {@code @ApplicationModuleTest} removes, after configuration parsing, the
 * bean definitions whose type belongs to a module the test does not boot. The store-scope hook (D165) and advisor
 * (D166) are beans of {@code config}, which no module test boots, so an {@code @Import(StoreScopeConfiguration)} was
 * dropped too: no connection carried a scope and every protected table raised {@code RS001}, and jobs and listeners ran
 * unscoped. {@link StoreScopePlumbing} runs after that filter (bean factory post-processors run after every registry
 * post-processor) and, when no {@link StoreScopeConfiguration} definition is left, registers the production factory
 * methods again under their production names. In a full context it does nothing, so there is exactly one hook and one
 * advisor. This class lives in the root package, which belongs to no module, so the filter keeps it.
 *
 * <p><b>Fixture fallback.</b> SQL in a test body on the JUnit thread with neither an actor nor an explicit scope sees
 * every store ({@link StoreScopeTestSupport}); a request dispatched by MockMvc does not ({@link FixtureFreeRequests}).
 * In a context without the {@code access} module there is no {@code StoreScopes}, so nothing is scoped (fail closed).
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

    /** A request dispatched by MockMvc on the JUnit thread does not inherit the fixture fallback. */
    @Bean
    FixtureFreeRequests fixtureFreeRequests() {
        return new FixtureFreeRequests();
    }

    /** Restores {@link StoreScopeConfiguration}'s two beans where a module test dropped them. */
    static final class StoreScopePlumbing implements BeanFactoryPostProcessor {

        static final String HOOK = "storeScopeDataSourcePostProcessor";
        static final String ADVISOR = "storeScopeAdvisor";

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
            if (!(factory instanceof BeanDefinitionRegistry registry)
                    || factory.getBeanNamesForType(StoreScopeConfiguration.class, true, false).length > 0) {
                return;
            }
            registry.registerBeanDefinition(HOOK, productionFactoryMethod(HOOK, BeanDefinition.ROLE_APPLICATION));
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
