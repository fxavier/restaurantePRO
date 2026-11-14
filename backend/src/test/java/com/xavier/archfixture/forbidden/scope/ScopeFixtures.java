package com.xavier.archfixture.forbidden.scope;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;

/** ADR 0039 (S64) fixtures: each "Unscoped…"/"Storeless…"/"Unpropagated…"/"Pool…" class breaks one store-scope rule. */
final class ScopeFixtures {

    private ScopeFixtures() {
    }

    record StoreEvent(UUID orderId, UUID storeId) {
    }

    record StorelessEvent(String name) {
    }

    static class UnscopedJob {
        @Scheduled(fixedDelay = 1000)
        void tick() {
        }
    }

    static class ScopedJob {
        @SystemScoped(SystemScope.HOUSEKEEPING)
        @Scheduled(fixedDelay = 1000)
        void tick() {
        }
    }

    static class UnscopedRunner implements ApplicationRunner {
        @Override
        public void run(ApplicationArguments args) {
        }
    }

    static class UnscopedReadiness {
        @EventListener(ApplicationReadyEvent.class)
        void ready() {
        }
    }

    static class StoreListener {
        @ApplicationModuleListener
        void on(StoreEvent event) {
        }
    }

    static class StorelessListener {
        @ApplicationModuleListener
        void on(StorelessEvent event) {
        }
    }

    static class ScopedStorelessListener {
        @SystemScoped(SystemScope.BOOTSTRAP)
        @ApplicationModuleListener
        void on(StorelessEvent event) {
        }
    }

    static class UnpropagatedSubmitter {
        ExecutorService pool;

        void go() {
            pool.submit(() -> { });
        }
    }

    static class PropagatedSubmitter {
        Executor pool;
        StoreScopes scopes;

        void go() {
            pool.execute(scopes.propagate(() -> { }));
        }
    }

    static class PoolOutsideConfig {
        HikariDataSource pool;
    }
}
