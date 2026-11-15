package com.xavier.backend.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D42 as amended by D179 (A82): the probe of a resource's store, {@code shared.store_of} (SECURITY DEFINER), now also
 * requires the bound tenant. Another store of the same tenant answers its store (the caller then refuses: 403);
 * another tenant's row, of the same table and with the same codes, answers nothing (404, existence never revealed);
 * with no tenant bound the probe fails closed ({@code RS003}). On {@code organization.terminal}, the TENANT table that
 * carries a {@code store_id}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@StrictTenantScope
class StoreProbeTenantTest {

    static final UUID A_STORE = UUID.fromString("0190b500-0000-7000-8000-0000000000a1");
    static final UUID A_OTHER_STORE = UUID.fromString("0190b500-0000-7000-8000-0000000000a2");
    static final UUID B_STORE = UUID.fromString("0190b500-0000-7000-8000-0000000000b1");
    static final UUID A_OTHER_TERMINAL = UUID.fromString("0190b500-0000-7000-8000-0000000001a2");
    static final UUID B_TERMINAL = UUID.fromString("0190b500-0000-7000-8000-0000000001b1");

    @Autowired
    StoreProbe probe;
    @Autowired
    TenantScopes tenants;
    @Autowired
    PlatformTransactionManager transactions;

    @BeforeAll
    static void storesAndTerminalsWithIdenticalCodes() {
        TenantTestSupport.ensureTenants();
        seed(TenantTestSupport.A, A_STORE, "PRB-1", UUID.randomUUID());
        seed(TenantTestSupport.A, A_OTHER_STORE, "PRB-2", A_OTHER_TERMINAL);
        seed(TenantTestSupport.B, B_STORE, "PRB-1", B_TERMINAL);
    }

    private static void seed(UUID tenant, UUID store, String code, UUID terminal) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'Probe', 'Africa/Maputo', true, 0, now(), now())").param(store)
                    .param(code).update();
            app.jdbc().sql("INSERT INTO organization.terminal (id, store_id, code, name, status, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'T1', 'Till', 'REGISTERED', 0, now(), now())").param(terminal)
                    .param(store).update();
        }
    }

    @Test
    void anotherStoreOfTheTenantAnswersItsStoreSoTheCallerRefusesIt403() {
        assertThat(probeIn(TenantTestSupport.A, A_OTHER_TERMINAL)).contains(A_OTHER_STORE);
    }

    @Test
    void anotherTenantsRowAnswersNothingSoItIsAbsent404() {
        assertThat(probeIn(TenantTestSupport.A, B_TERMINAL)).isEmpty();
        assertThat(probeIn(TenantTestSupport.B, B_TERMINAL)).as("its own tenant finds it").contains(B_STORE);
    }

    @Test
    void anUnknownIdAndAnotherTenantsIdAnswerTheSame() {
        assertThat(probeIn(TenantTestSupport.A, UUID.randomUUID())).isEqualTo(probeIn(TenantTestSupport.A, B_TERMINAL));
    }

    @Test
    void withNoTenantBoundTheProbeFailsClosed() {
        AppRoleSession.assertRefused(() -> new TransactionTemplate(transactions).execute(status ->
                probe.storeOf("organization.terminal", B_TERMINAL)), StoreScopeViolations.Kind.TENANT_MISSING);
    }

    private Optional<UUID> probeIn(UUID tenant, UUID terminal) {
        return TenantTestSupport.inTenant(tenants, tenant, () -> new TransactionTemplate(transactions)
                .execute(status -> probe.storeOf("organization.terminal", terminal)));
    }
}
