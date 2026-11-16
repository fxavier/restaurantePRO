package com.xavier.backend.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;

/**
 * D186 (security rule 7): the Idempotency-Key space is per tenant. The same operation scope and key in tenants A and
 * B are two independent records (key {@code (tenant_id, scope, key)}, row-level security on {@code tenant_id}): B's
 * request runs its own action instead of replaying A's response, a different body in B is not a reused key, and each
 * tenant replays only its own response. Strict: no bootstrap-tenant fallback.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@StrictTenantScope
class IdempotencyTenantScopeTest {

    @Autowired
    IdempotencyService idempotency;
    @Autowired
    TenantScopes tenants;

    record Answer(String tenant) {
    }

    @BeforeAll
    static void tenantsExist() {
        TenantTestSupport.ensureTenants();
    }

    @Test
    void theSameKeyInTwoTenantsIsTwoRecordsAndEachReplaysItsOwn() {
        String scope = "orders.order.close:EMPLOYEE:" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        AtomicInteger runs = new AtomicInteger();

        Answer inA = execute(TenantTestSupport.A, scope, key, Map.of("note", "a"), "A", runs);
        Answer inB = execute(TenantTestSupport.B, scope, key, Map.of("note", "b"), "B", runs);
        Answer replayA = execute(TenantTestSupport.A, scope, key, Map.of("note", "a"), "must not run", runs);
        Answer replayB = execute(TenantTestSupport.B, scope, key, Map.of("note", "b"), "must not run", runs);

        assertThat(List.of(inA, inB, replayA, replayB)).containsExactly(new Answer("A"), new Answer("B"),
                new Answer("A"), new Answer("B"));
        assertThat(runs).as("once per tenant").hasValue(2);
        // one record per tenant for the same (scope, key): the tenant is the record's column (and RLS), not a string
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT tenant_id || ' ' || scope FROM shared.idempotency_record "
                        + "WHERE key = ?").param(key).query(String.class).list())
                .containsExactlyInAnyOrder(TenantTestSupport.A + " " + scope, TenantTestSupport.B + " " + scope);
    }

    private Answer execute(UUID tenant, String scope, String key, Map<String, String> body, String answer,
                           AtomicInteger runs) {
        ResponseEntity<Answer> response = TenantTestSupport.inTenant(tenants, tenant, () -> idempotency.execute(scope,
                key, "POST /api/v1/pos/orders/x/close", body, Answer.class, () -> {
                    runs.incrementAndGet();
                    return ResponseEntity.ok(new Answer(answer));
                }));
        return response.getBody();
    }
}
