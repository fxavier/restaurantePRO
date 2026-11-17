package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * S77 (F1): {@code db_transactions{kind}} groups every begin under a low-cardinality kind, never a tenant, an actor or
 * a listener name (P74).
 */
class TransactionOriginKindTest {

    @Test
    void aTransactionBegunWhileServingAnHttpRequestIsARequestWhateverItsScope() {
        assertThat(TenantBindingTransactionManager.originKind(null, "employee:e@t", true)).isEqualTo("request");
        assertThat(TenantBindingTransactionManager.originKind(null, "none", true)).as("authentication, unbound")
                .isEqualTo("request");
        assertThat(TenantBindingTransactionManager.originKind(null, "system:PLATFORM", true)).isEqualTo("request");
    }

    @Test
    void anActorsScopeIsARequestOffTheServletThreadToo() {
        assertThat(TenantBindingTransactionManager.originKind(null, "account:0190", false)).isEqualTo("request");
        assertThat(TenantBindingTransactionManager.originKind(null, "employee:e@t", false)).isEqualTo("request");
    }

    @Test
    void theEventRegistrysOwnTransitionsAreTheRegistry() {
        assertThat(TenantBindingTransactionManager.originKind(
                "org.springframework.modulith.events.core.DefaultEventPublicationRegistry.markCompleted",
                "listener:PrintListener.on", false)).isEqualTo("registry");
    }

    @Test
    void aListenerIsOneKindWhateverItsName() {
        assertThat(TenantBindingTransactionManager.originKind("x.Y.on", "listener:ProductionListener.on", false))
                .isEqualTo("listener");
    }

    @Test
    void aSystemScopeKeepsItsNameFromTheReviewedAllowList() {
        assertThat(TenantBindingTransactionManager.originKind(null, "system:PRINT_WORKER", false))
                .isEqualTo("system:PRINT_WORKER");
    }

    @Test
    void anythingElseIsUnbound() {
        assertThat(TenantBindingTransactionManager.originKind(null, "none", false)).isEqualTo("unbound");
        assertThat(TenantBindingTransactionManager.originKind(null, "task:system:ALERTS", false))
                .isEqualTo("unbound");
    }
}
