package com.xavier.backend.settlement.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.TestAuthentications;
import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The POS alert settlement owns (light reference: "1 pagamento por confirmar"): the session's store's payment attempts
 * in doubt (UNKNOWN, §7.4), with the order of the oldest one that has an order, whose payment screen resolves it. Only
 * for who collects or resolves payments; the bound tenant's store only.
 */
@SettlementModuleTest
@StrictTenantScope
class SettlementAlertsTest {

    static final String ALERTS = "/api/v1/pos/settlement/alerts";
    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    @Autowired
    MockMvc mvc;

    UUID store;

    @BeforeEach
    void aStoreOfTenantA() {
        TenantTestSupport.ensureTenants();
        store = store(A);
    }

    @Test
    void aStoreWithNothingInDoubtHasNoPaymentToConfirm() throws Exception {
        mvc.perform(get(ALERTS).with(pos(A, store, Permission.PAYMENT_COLLECT))).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsToConfirm").value(0))
                .andExpect(jsonPath("$.oldestOrderId").doesNotExist());
    }

    @Test
    void attemptsInDoubtArePaymentsToConfirmAndTheOldestOrderResolvesFirst() throws Exception {
        UUID oldest = UUID.randomUUID();
        attempt(A, store, oldest, "UNKNOWN", 40);
        attempt(A, store, UUID.randomUUID(), "UNKNOWN", 10);
        attempt(A, store, UUID.randomUUID(), "PENDING", 50);
        attempt(A, store, UUID.randomUUID(), "SUCCEEDED", 60);

        mvc.perform(get(ALERTS).with(pos(A, store, Permission.PAYMENT_RESOLVE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsToConfirm").value(2))
                .andExpect(jsonPath("$.oldestOrderId").value(oldest.toString()));
    }

    @Test
    void anotherStoresAndAnotherTenantsAttemptsAreNotCounted() throws Exception {
        UUID otherStore = store(A);
        UUID otherTenantsStore = store(B);
        attempt(A, otherStore, UUID.randomUUID(), "UNKNOWN", 20);
        attempt(B, otherTenantsStore, UUID.randomUUID(), "UNKNOWN", 20);

        mvc.perform(get(ALERTS).with(pos(A, store, Permission.PAYMENT_COLLECT))).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsToConfirm").value(0));
        mvc.perform(get(ALERTS).with(pos(B, otherTenantsStore, Permission.PAYMENT_COLLECT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentsToConfirm").value(1));
        mvc.perform(get(ALERTS).with(pos(A, otherTenantsStore, Permission.PAYMENT_COLLECT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAlertNeedsAPosSessionThatCollectsOrResolvesPayments() throws Exception {
        mvc.perform(get(ALERTS).with(pos(A, store, Permission.ORDER_VIEW))).andExpect(status().isForbidden());
        Actor account = Actor.account(UUID.randomUUID(), Set.of(Permission.PAYMENT_RESOLVE.authority(store)));
        mvc.perform(get(ALERTS).with(TestAuthentications.as(account, A))).andExpect(status().isForbidden());
        mvc.perform(get(ALERTS)).andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor pos(UUID tenant, UUID inStore, Permission... permissions) {
        Set<String> authorities = Arrays.stream(permissions).map(p -> p.authority(inStore))
                .collect(Collectors.toSet());
        return TestAuthentications.as(Actor.employee(UUID.randomUUID(), UUID.randomUUID(), inStore, authorities),
                tenant);
    }

    private static UUID store(UUID tenant) {
        UUID id = UUID.randomUUID();
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("""
                    INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, updated_at)
                    VALUES (?, ?, 'Alertas', 'Africa/Maputo', true, 0, now(), now())""")
                    .param(id).param("SA-" + id.toString().substring(0, 8)).update();
        }
        return id;
    }

    /** An integrated attempt of {@code order} in {@code status}, created {@code minutesAgo}. */
    private static void attempt(UUID tenant, UUID inStore, UUID order, String status, int minutesAgo) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("""
                    INSERT INTO settlement.payment_attempt (id, order_id, store_id, terminal_id, session_id,
                        method_code, method_kind, provider_code, provider_reference, amount, currency, status,
                        created_by_employee_id, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, 'FAKE_M', 'INTEGRATED', 'FAKE', ?, 25.00, 'MZN', ?, ?,
                        now() - make_interval(mins => ?), now(), 0)""")
                    .params(UUID.randomUUID(), order, inStore, UUID.randomUUID(), UUID.randomUUID(),
                            UUID.randomUUID().toString(), status, UUID.randomUUID(), minutesAgo).update();
        }
    }
}
