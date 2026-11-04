package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig({AccessPolicy.class, AccessPolicyTest.Stores.class})
@ExtendWith(OutputCaptureExtension.class)
class AccessPolicyTest {

    static final String STORE_A = "0190a000-0000-7000-8000-00000000000a";
    static final String STORE_B = "0190b000-0000-7000-8000-00000000000b";
    static final String INACTIVE_STORE = "0190c000-0000-7000-8000-00000000000c";
    static final String EMPLOYEE = "0190e000-0000-7000-8000-00000000000e";
    static final String TERMINAL = "0190f000-0000-7000-8000-00000000000f";

    @Autowired
    AccessPolicy accessPolicy;

    @Test
    @WithActor(permissions = Permission.CATALOG_MANAGE)
    void deniesAPermissionTheActorDoesNotHave() {
        assertThatCode(() -> accessPolicy.require(Permission.CATALOG_MANAGE)).doesNotThrowAnyException();

        assertThatThrownBy(() -> accessPolicy.require(Permission.CATALOG_PUBLISH))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithActor(permissions = Permission.DINING_MANAGE, storeId = STORE_A)
    void aStorePermissionOnlyCountsInThatStore() {
        assertThatCode(() -> accessPolicy.require(Permission.DINING_MANAGE, uuid(STORE_A)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> accessPolicy.require(Permission.DINING_MANAGE, uuid(STORE_B)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accessPolicy.require(Permission.DINING_MANAGE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithActor(permissions = Permission.CATALOG_PUBLISH)
    void anInstallationWidePermissionCountsInEveryStore() {
        assertThatCode(() -> accessPolicy.require(Permission.CATALOG_PUBLISH, uuid(STORE_A)))
                .doesNotThrowAnyException();
        assertThatCode(() -> accessPolicy.require(Permission.CATALOG_PUBLISH, uuid(STORE_B)))
                .doesNotThrowAnyException();
    }

    @Test
    @WithActor(permissions = Permission.DINING_VIEW, storeId = STORE_A, employeeId = EMPLOYEE, terminalId = TERMINAL)
    void currentIsTheAuthenticatedActor() {
        Actor actor = accessPolicy.current();

        assertThat(actor.kind()).isEqualTo(ActorKind.EMPLOYEE);
        assertThat(actor.userId()).isEqualTo(uuid(EMPLOYEE)); // the principal id of a POS session (D38)
        assertThat(actor.employeeId()).isEqualTo(uuid(EMPLOYEE));
        assertThat(actor.terminalId()).isEqualTo(uuid(TERMINAL));
        assertThat(actor.storeId()).isEqualTo(uuid(STORE_A));
        assertThat(actor.authorities()).containsExactly("PERM_DINING_VIEW@" + STORE_A);
    }

    @Test
    @WithActor(permissions = Permission.DINING_VIEW)
    void aBackOfficeActorIsAnAccount() {
        Actor actor = accessPolicy.current();

        assertThat(actor.kind()).isEqualTo(ActorKind.ACCOUNT);
        assertThat(actor.userId()).isEqualTo(uuid(WithActor.DEFAULT_USER_ID));
        assertThat(actor.employeeId()).isNull();
        assertThat(actor.idempotencyScope("orders.round.confirm"))
                .isEqualTo("orders.round.confirm:ACCOUNT:" + WithActor.DEFAULT_USER_ID);
    }

    @Test
    @WithActor(permissions = Permission.DINING_MANAGE, storeId = STORE_A, employeeId = EMPLOYEE, terminalId = TERMINAL)
    void requirePosReturnsTheSessionsEmployeeTerminalAndStore() {
        PosContext pos = accessPolicy.requirePos(Permission.DINING_MANAGE);

        assertThat(pos).isEqualTo(new PosContext(uuid(EMPLOYEE), uuid(TERMINAL), uuid(STORE_A)));
    }

    @Test
    @WithActor(permissions = Permission.DINING_MANAGE)
    void requirePosRefusesABackOfficeSessionEvenWithAnInstallationWideGrant() {
        assertThatThrownBy(() -> accessPolicy.requirePos(Permission.DINING_MANAGE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requirePosRefusesAnEmployeeWithoutATerminal() {
        Actor noTerminal = Actor.employee(uuid(EMPLOYEE), null, uuid(STORE_A),
                Set.of(Permission.DINING_MANAGE.authority(uuid(STORE_A))));
        runAs(noTerminal, () -> assertThatThrownBy(() -> accessPolicy.requirePos(Permission.DINING_MANAGE))
                .isInstanceOf(AccessDeniedException.class));
    }

    @Test
    @WithActor(permissions = Permission.DINING_VIEW, storeId = STORE_A, employeeId = EMPLOYEE, terminalId = TERMINAL)
    void requirePosRefusesAPermissionMissingInTheSessionStore() {
        assertThatThrownBy(() -> accessPolicy.requirePos(Permission.DINING_MANAGE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requirePosRefusesAPermissionGrantedOnlyInAnotherStore() {
        Actor elsewhere = Actor.employee(uuid(EMPLOYEE), uuid(TERMINAL), uuid(STORE_A),
                Set.of(Permission.DINING_MANAGE.authority(uuid(STORE_B))));
        runAs(elsewhere, () -> assertThatThrownBy(() -> accessPolicy.requirePos(Permission.DINING_MANAGE))
                .isInstanceOf(AccessDeniedException.class));
    }

    @Test
    @WithActor(permissions = Permission.DINING_MANAGE, storeId = INACTIVE_STORE, employeeId = EMPLOYEE,
            terminalId = TERMINAL)
    void requirePosRefusesAnInactiveStore(CapturedOutput output) {
        // D45: covers the window before the StoreDeactivated listener ends the session
        assertThatThrownBy(() -> accessPolicy.requirePos(Permission.DINING_MANAGE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("inactive");
        assertThat(output.getOut()).contains("store inactive").contains(INACTIVE_STORE);
    }

    @Test
    @WithActor(permissions = Permission.PRODUCTION_VIEW, storeId = STORE_A, employeeId = EMPLOYEE,
            terminalId = TERMINAL)
    void requirePosAnyAcceptsAnyOneOfThePermissionsAndRefusesNone(CapturedOutput output) {
        assertThat(accessPolicy.requirePosAny(Permission.DINING_VIEW, Permission.PRODUCTION_VIEW).storeId())
                .isEqualTo(uuid(STORE_A));

        assertThatThrownBy(() -> accessPolicy.requirePosAny(Permission.DINING_VIEW, Permission.ORDER_VIEW))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(output.getOut()).contains("[DINING_VIEW, ORDER_VIEW]");
    }

    @Test
    void anEmployeeActorsPrincipalIdIsItsEmployeeId() {
        assertThatThrownBy(() -> new Actor(ActorKind.EMPLOYEE, UUID.randomUUID(), UUID.randomUUID(), null, null,
                Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorKind.ACCOUNT, UUID.randomUUID(), UUID.randomUUID(), null, null,
                Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(null, UUID.randomUUID(), null, null, null, Set.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void withoutAnAuthenticatedActorEverythingIsRefused() {
        assertThatThrownBy(() -> accessPolicy.current())
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> accessPolicy.require(Permission.CATALOG_VIEW))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> accessPolicy.require(Permission.CATALOG_VIEW, uuid(STORE_A)))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void theActorsAuthoritiesAreTheOnlySourceOfTruth() {
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(Permission.CATALOG_VIEW.authority()));
        // the Authentication claims something else; AccessPolicy must decide from the Actor alone
        var authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(Permission.CATALOG_PUBLISH.authority())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            assertThatCode(() -> accessPolicy.require(Permission.CATALOG_VIEW)).doesNotThrowAnyException();
            assertThatThrownBy(() -> accessPolicy.require(Permission.CATALOG_PUBLISH))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @WithActor(permissions = Permission.DINING_VIEW, storeId = STORE_A)
    void aDenialIsLoggedWithWhoWhatAndWhere(CapturedOutput output) {
        assertThatThrownBy(() -> accessPolicy.require(Permission.DINING_MANAGE, uuid(STORE_B)))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(output.getOut())
                .contains("Access denied")
                .contains(WithActor.DEFAULT_USER_ID)
                .contains("DINING_MANAGE")
                .contains(STORE_B);
    }

    private static void runAs(Actor actor, Runnable check) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
        try {
            check.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Organization implements the SPI in the application; here every store but {@link #INACTIVE_STORE} is active. */
    static class Stores implements StoreActivity {
        @Override
        public boolean isActive(UUID storeId) {
            return !storeId.equals(uuid(INACTIVE_STORE));
        }
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
