package com.xavier.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * In a full context {@code config} is scanned, so {@link StoreScopeTestAutoConfiguration} must not add a second hook or
 * advisor (a second advisor would nest every system scope and count each refusal twice).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public class StoreScopeTestAutoConfigurationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void aFullContextHasExactlyOneHookAndOneAdvisor() {
        assertThat(storeScopePlumbing(context)).containsExactly("StoreScopeDataSourcePostProcessor",
                "StoreScopeAdvisor");
    }

    /** The simple class names of the store-scope hook and advisor beans of {@code context} (both package-private). */
    public static List<String> storeScopePlumbing(ApplicationContext context) {
        return Stream.concat(
                        context.getBeansOfType(BeanPostProcessor.class, true, false).values().stream(),
                        context.getBeansOfType(Advisor.class, true, false).values().stream())
                .map(bean -> bean.getClass().getSimpleName())
                .filter(name -> name.equals("StoreScopeDataSourcePostProcessor") || name.equals("StoreScopeAdvisor"))
                .toList();
    }
}
