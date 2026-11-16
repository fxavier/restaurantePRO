package com.xavier.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * In a full context {@code config} is scanned, so {@link StoreScopeTestAutoConfiguration} must not add a second
 * transaction manager or advisor (a second advisor would nest every system scope and count each refusal twice).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public class StoreScopeTestAutoConfigurationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void aFullContextHasExactlyOneTransactionManagerAndOneAdvisor() {
        assertThat(storeScopePlumbing(context)).containsExactly("TenantBindingTransactionManager",
                "StoreScopeAdvisor");
    }

    /**
     * The simple class names of the transaction managers and of the scope advisor of {@code context}: exactly the
     * tenant-binding one and the advisor (any other transaction manager would begin unbound transactions).
     */
    public static List<String> storeScopePlumbing(ApplicationContext context) {
        return Stream.concat(
                        context.getBeansOfType(PlatformTransactionManager.class, true, false).values().stream(),
                        context.getBeansOfType(Advisor.class, true, false).values().stream()
                                .filter(bean -> bean.getClass().getSimpleName().equals("StoreScopeAdvisor")))
                .map(bean -> bean.getClass().getSimpleName())
                .toList();
    }
}
