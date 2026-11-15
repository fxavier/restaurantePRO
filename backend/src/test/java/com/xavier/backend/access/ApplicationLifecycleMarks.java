package com.xavier.backend.access;

import java.time.Duration;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;

/**
 * Test-only (registered in {@code META-INF/spring.factories} of the test resources), D171: an application's startup
 * and shutdown are never fixture code, even when a test body runs them on its marked JUnit thread (a simulated
 * restart, {@code new SpringApplicationBuilder(...).run()} and its {@code close()}). From {@link #starting} until
 * {@link #ready} or {@link #failed} the thread is unmarked, so runners, ready-event listeners, lifecycle starts,
 * republication and every {@code StoreScopes.propagate} captured meanwhile get production's scope. {@link CloseMark}
 * does the same from the context's {@link ContextClosedEvent} to the end of its bean destruction. The marks before and
 * after are the test's own, so its SQL around {@code run()} and {@code close()} keeps the fallback.
 */
public class ApplicationLifecycleMarks implements SpringApplicationRunListener {

    private boolean starting;

    public ApplicationLifecycleMarks(SpringApplication application, String[] args) {
    }

    @Override
    public void starting(ConfigurableBootstrapContext bootstrapContext) {
        StoreScopeTestSupport.enter(false);
        starting = true;
    }

    @Override
    public void contextPrepared(ConfigurableApplicationContext context) {
        CloseMark mark = new CloseMark(context);
        context.addApplicationListener(mark);
        if (context.getBeanFactory() instanceof DefaultSingletonBeanRegistry registry) {
            // registered before the refresh, so destroyed after every bean of the application
            registry.registerDisposableBean(CloseMark.class.getName(), mark);
        }
    }

    @Override
    public void ready(ConfigurableApplicationContext context, Duration timeTaken) {
        started();
    }

    @Override
    public void failed(ConfigurableApplicationContext context, Throwable exception) {
        started();
    }

    private void started() {
        if (starting) {
            starting = false;
            StoreScopeTestSupport.leave();
        }
    }

    /**
     * Unmarks the closing thread from the first closed-event listener to the last bean destroyed. Only for its own
     * context's event: a child context (Boot's management context on its own port) closes inside its parent's close,
     * and its closed event reaches the parent's listeners too.
     */
    static final class CloseMark implements ApplicationListener<ContextClosedEvent>, DisposableBean, Ordered {

        private final ConfigurableApplicationContext context;
        private boolean closing;

        CloseMark(ConfigurableApplicationContext context) {
            this.context = context;
        }

        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            if (event.getApplicationContext() != context) {
                return;
            }
            StoreScopeTestSupport.enter(false);
            closing = true;
        }

        @Override
        public void destroy() {
            if (closing) {
                closing = false;
                StoreScopeTestSupport.leave();
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
