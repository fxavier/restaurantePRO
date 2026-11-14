package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D164: how an {@link Actor} maps onto the three database settings, one row of the D164 table per test. */
class StoreScopeTest {

    static final UUID ACCOUNT = UUID.fromString("0190a000-0000-7000-8000-00000000a001");
    static final UUID EMPLOYEE = UUID.fromString("0190a000-0000-7000-8000-00000000e001");
    static final UUID TERMINAL = UUID.fromString("0190a000-0000-7000-8000-00000000f001");
    static final UUID STORE_A = UUID.fromString("0190a000-0000-7000-8000-0000000000aa");
    static final UUID STORE_B = UUID.fromString("0190A000-0000-7000-8000-0000000000BB");

    @Test
    void anAccountWithAnInstallationWideAuthoritySeesEveryStore() {
        StoreScope scope = StoreScope.of(Actor.account(ACCOUNT, Set.of(
                Permission.REPORT_VIEW.authority(STORE_A), Permission.CATALOG_VIEW.authority())));

        assertThat(scope.allStores()).isTrue();
        assertThat(scope.allStoresSetting()).isEqualTo("on");
        assertThat(scope.storeIdsLiteral()).isEqualTo("{}");
        assertThat(scope.origin()).isEqualTo("account:" + ACCOUNT);
    }

    @Test
    void anAccountWithOnlyStoreScopedAuthoritiesSeesThoseStores() {
        StoreScope scope = StoreScope.of(Actor.account(ACCOUNT, Set.of(
                Permission.REPORT_VIEW.authority(STORE_B), Permission.CASH_VIEW.authority(STORE_A),
                Permission.ORDER_VIEW.authority(STORE_A))));

        assertThat(scope.allStores()).isFalse();
        assertThat(scope.allStoresSetting()).isEqualTo("off");
        assertThat(scope.stores()).containsExactlyInAnyOrder(STORE_A, STORE_B);
        assertThat(scope.storeIdsLiteral()).as("sorted, lower case")
                .isEqualTo("{" + STORE_A + "," + STORE_B.toString().toLowerCase() + "}");
    }

    @Test
    void anAccountThatMustChangeItsPasswordSeesNoStore() {
        StoreScope scope = StoreScope.of(Actor.account(ACCOUNT, Set.of()));

        assertThat(scope.allStoresSetting()).isEqualTo("off");
        assertThat(scope.storeIdsLiteral()).isEqualTo("{}");
        assertThat(scope.isSet()).isTrue();
    }

    @Test
    void authoritiesThatAreNotPermissionsGrantNoStore() {
        StoreScope scope = StoreScope.of(Actor.account(ACCOUNT, Set.of("ROLE_USER", "FACTOR_PASSWORD",
                "PERM_X@not-a-uuid")));

        assertThat(scope.allStores()).isFalse();
        assertThat(scope.storeIdsLiteral()).isEqualTo("{}");
    }

    @Test
    void aPosSessionSeesItsTerminalStoreWhenItHoldsAnAuthorityThere() {
        StoreScope scoped = StoreScope.of(Actor.employee(EMPLOYEE, TERMINAL, STORE_A,
                Set.of(Permission.ORDER_EDIT.authority(STORE_A), Permission.ORDER_VIEW.authority(STORE_B))));
        StoreScope global = StoreScope.of(Actor.employee(EMPLOYEE, TERMINAL, STORE_A,
                Set.of(Permission.ORDER_EDIT.authority())));

        assertThat(scoped.allStoresSetting()).as("a POS session is never all stores").isEqualTo("off");
        assertThat(scoped.storeIdsLiteral()).as("only the terminal's store").isEqualTo("{" + STORE_A + "}");
        assertThat(global.allStoresSetting()).isEqualTo("off");
        assertThat(global.storeIdsLiteral()).isEqualTo("{" + STORE_A + "}");
        assertThat(scoped.origin()).isEqualTo("employee:" + EMPLOYEE + "@" + TERMINAL);
    }

    @Test
    void aPosSessionWithNoAuthorityInItsStoreSeesNoStore() {
        StoreScope scope = StoreScope.of(Actor.employee(EMPLOYEE, TERMINAL, STORE_A,
                Set.of(Permission.ORDER_EDIT.authority(STORE_B))));

        assertThat(scope.storeIdsLiteral()).isEqualTo("{}");
        assertThat(scope.allStoresSetting()).isEqualTo("off");
    }

    @Test
    void namedScopesAndTheUnsetScope() {
        assertThat(StoreScope.all("system:X").allStoresSetting()).isEqualTo("on");
        assertThat(StoreScope.all("system:X").storeIdsLiteral()).isEqualTo("{}");
        assertThat(StoreScope.none("system:Y").allStoresSetting()).isEqualTo("off");
        assertThat(StoreScope.none("system:Y").storeIdsLiteral()).isEqualTo("{}");
        assertThat(StoreScope.UNSET.isSet()).isFalse();
        assertThat(StoreScope.UNSET.allStoresSetting()).as("'' means unset (D164)").isEmpty();
        assertThat(StoreScope.UNSET.storeIdsLiteral()).isEmpty();
        assertThat(StoreScope.UNSET.origin()).isEqualTo("none");
    }

    @Test
    void aStoreScopeOfOneStore() {
        StoreScope scope = StoreScope.ofStore(STORE_B, "listener:X.on");

        assertThat(scope.allStoresSetting()).isEqualTo("off");
        assertThat(scope.storeIdsLiteral()).isEqualTo("{" + STORE_B.toString().toLowerCase() + "}");
    }

    @Test
    void everySystemScopeHasItsReach() {
        assertThat(SystemScope.BOOTSTRAP.scope().allStoresSetting()).isEqualTo("on");
        assertThat(SystemScope.BOOTSTRAP.scope().origin()).isEqualTo("system:BOOTSTRAP");
        assertThat(SystemScope.HOUSEKEEPING.scope().allStoresSetting()).isEqualTo("off");
        assertThat(SystemScope.HOUSEKEEPING.scope().storeIdsLiteral()).isEqualTo("{}");
        for (SystemScope scope : SystemScope.values()) {
            assertThat(scope.reason()).as(scope.name()).isNotBlank();
        }
        assertThat(SystemScope.values()).filteredOn(s -> s.reach() == SystemScope.Reach.ALL)
                .containsExactlyInAnyOrder(SystemScope.BOOTSTRAP, SystemScope.PRINT_WORKER,
                        SystemScope.PAYMENT_RECOVERY, SystemScope.RESERVATIONS, SystemScope.STOCK_RECONCILE,
                        SystemScope.REPORTING_RECOVERY, SystemScope.METRICS, SystemScope.ALERTS);
        assertThat(SystemScope.values()).filteredOn(s -> s.reach() == SystemScope.Reach.NONE)
                .containsExactlyInAnyOrder(SystemScope.EVENT_MAINTENANCE, SystemScope.IDENTITY_MAINTENANCE,
                        SystemScope.HOUSEKEEPING);
    }
}
