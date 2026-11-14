package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.StoreScope;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.modulith.events.ApplicationModuleListener;

/** D166: what the advisor applies, before anything else of the method (its transaction included). */
class StoreScopeAdvisorTest {

    static final UUID STORE = UUID.fromString("0190a000-0000-7000-8000-0000000000aa");

    record StoreEvent(UUID orderId, UUID storeId) {
    }

    record StorelessEvent(String name) {
    }

    static class Entry {

        StoreScopes scopes;

        @SystemScoped(SystemScope.RESERVATIONS)
        public StoreScope job() {
            return scopes.current();
        }

        @ApplicationModuleListener
        public StoreScope on(StoreEvent event) {
            return scopes.current();
        }

        @ApplicationModuleListener
        public StoreScope on(StorelessEvent event) {
            return scopes.current();
        }

        @SystemScoped(SystemScope.BOOTSTRAP)
        @ApplicationModuleListener
        public StoreScope onBootstrap(StorelessEvent event) {
            return scopes.current();
        }

        @SystemScoped(SystemScope.HOUSEKEEPING)
        public void refused() {
            throw new DataAccessResourceFailureException("x", new SQLException("store scope not set", "RS001"));
        }

        public StoreScope plain() {
            return scopes.current();
        }
    }

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final StoreScopes scopes = new StoreScopes(() -> StoreScope.UNSET,
            new StoreScopeViolations(registry, Clock.systemUTC()));
    final Entry entry = proxied();

    @Test
    void aSystemScopedMethodRunsInItsSystemScope() {
        StoreScope inside = entry.job();

        assertThat(inside.origin()).isEqualTo("system:RESERVATIONS");
        assertThat(inside.allStoresSetting()).isEqualTo("on");
        assertThat(scopes.current()).as("restored after").isEqualTo(StoreScope.UNSET);
    }

    @Test
    void aListenerRunsInItsEventsStore() {
        StoreScope inside = entry.on(new StoreEvent(UUID.randomUUID(), STORE));

        assertThat(inside.storeIdsLiteral()).isEqualTo("{" + STORE + "}");
        assertThat(inside.allStoresSetting()).isEqualTo("off");
        assertThat(inside.origin()).isEqualTo("listener:Entry.on");
    }

    @Test
    void aListenerOfAnEventWithoutAStoreFailsBeforeItRuns() {
        assertThatThrownBy(() -> entry.on(new StorelessEvent("x"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listener:Entry.on");
        assertThatThrownBy(() -> entry.on(new StoreEvent(UUID.randomUUID(), null)))
                .as("a null storeId too").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSystemScopeOutranksTheListenerRule() {
        assertThat(entry.onBootstrap(new StorelessEvent("x")).origin()).isEqualTo("system:BOOTSTRAP");
    }

    @Test
    void anUnannotatedMethodIsLeftAlone() {
        assertThat(entry.plain()).isEqualTo(StoreScope.UNSET);
    }

    @Test
    void aRefusalByRowLevelSecurityIsCountedWithItsOrigin() {
        assertThatThrownBy(entry::refused).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(registry.get("store_scope_violations").tag("kind", "scope_missing").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void itRunsBeforeTheTransactionInterceptor() {
        assertThat(new StoreScopeAdvisor(provider(scopes), provider(null)).getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    private Entry proxied() {
        Entry target = new Entry();
        target.scopes = scopes;
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new StoreScopeAdvisor(provider(scopes),
                provider(new StoreScopeViolations(registry, Clock.systemUTC()))));
        return (Entry) factory.getProxy();
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T bean) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (bean != null) {
            factory.registerSingleton("bean", bean);
        }
        @SuppressWarnings("unchecked")
        Class<T> type = bean == null ? (Class<T>) Object.class : (Class<T>) bean.getClass();
        return factory.getBeanProvider(type);
    }
}
