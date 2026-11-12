package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * D142: after a successful permission check, a BackOffice account using a sensitive permission needs a fresh second
 * factor when {@link AuthenticationAssurance} enforces step-up. POS sessions, non-sensitive permissions and an
 * installation without the SPI bean are never asked.
 */
class AccessPolicyStepUpTest {

    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    static final Duration MAX_AGE = Duration.ofMinutes(15);
    static final UUID STORE = UUID.fromString("0190a000-0000-7000-8000-00000000000a");
    static final UUID ACCOUNT = UUID.fromString("0190d000-0000-7000-8000-00000000000d");
    static final UUID EMPLOYEE = UUID.fromString("0190e000-0000-7000-8000-00000000000e");
    static final UUID TERMINAL = UUID.fromString("0190f000-0000-7000-8000-00000000000f");

    static final AssuranceState FRESH = enrolled(NOW.minus(Duration.ofMinutes(1)));
    static final AssuranceState STALE = enrolled(NOW.minus(MAX_AGE).minusNanos(1_000));
    static final AssuranceState NEVER = enrolled(null);
    static final AssuranceState NOT_ENROLLED = new AssuranceState(true, false, null, MAX_AGE);

    final AtomicInteger lookups = new AtomicInteger();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aFreshSecondFactorPassesASensitivePermission() {
        AccessPolicy policy = policy(FRESH);
        actAsAccount(Permission.USER_MANAGE.authority());

        assertThatCode(() -> policy.require(Permission.USER_MANAGE)).doesNotThrowAnyException();
    }

    @Test
    void aProofExactlyMaxAgeOldIsStillFresh() {
        AccessPolicy policy = policy(enrolled(NOW.minus(MAX_AGE)));
        actAsAccount(Permission.USER_MANAGE.authority());

        assertThatCode(() -> policy.require(Permission.USER_MANAGE)).doesNotThrowAnyException();
    }

    @Test
    void aProofOlderThanMaxAgeNeedsAStepUp() {
        AccessPolicy policy = policy(STALE);
        actAsAccount(Permission.USER_MANAGE.authority());

        assertStepUp(() -> policy.require(Permission.USER_MANAGE), StepUpRequiredException.STEP_UP_REQUIRED);
    }

    @Test
    void aSessionThatNeverProvedTheFactorNeedsAStepUp() {
        AccessPolicy policy = policy(NEVER);
        actAsAccount(Permission.AUDIT_EXPORT.authority());

        assertStepUp(() -> policy.require(Permission.AUDIT_EXPORT), StepUpRequiredException.STEP_UP_REQUIRED);
    }

    @Test
    void anAccountWithoutAFactorMustEnrolFirst() {
        AccessPolicy policy = policy(NOT_ENROLLED);
        actAsAccount(Permission.USER_MANAGE.authority());

        assertStepUp(() -> policy.require(Permission.USER_MANAGE), StepUpRequiredException.ENROLMENT_REQUIRED);
    }

    @Test
    void stepUpNotEnforcedLetsTheSensitivePermissionThrough() {
        AccessPolicy policy = policy(new AssuranceState(false, true, null, MAX_AGE));
        actAsAccount(Permission.USER_MANAGE.authority());

        assertThatCode(() -> policy.require(Permission.USER_MANAGE)).doesNotThrowAnyException();
    }

    @Test
    void withoutAnAssuranceBeanStepUpIsNotEnforced() {
        AccessPolicy policy = new AccessPolicy(new StaticListableBeanFactory().getBeanProvider(StoreActivity.class),
                new StaticListableBeanFactory().getBeanProvider(AuthenticationAssurance.class), clock());
        actAsAccount(Permission.USER_MANAGE.authority());

        assertThatCode(() -> policy.require(Permission.USER_MANAGE)).doesNotThrowAnyException();
    }

    @Test
    void aNonSensitivePermissionNeverAsksForTheFactor() {
        AccessPolicy policy = policy(NEVER);
        actAsAccount(Permission.CATALOG_MANAGE.authority());

        assertThatCode(() -> policy.require(Permission.CATALOG_MANAGE)).doesNotThrowAnyException();
        assertThat(lookups).hasValue(0);
    }

    /** D142 (amended, S55 review): a PIN session never reaches a sensitive BackOffice permission once enforced. */
    @Test
    void anEmployeeIsRefusedASensitivePermissionOutsideThePosGateWhenEnforced() {
        AccessPolicy policy = policy(FRESH);
        actAs(Actor.employee(EMPLOYEE, TERMINAL, STORE, Set.of(Permission.CASH_CLOSING_APPROVE.authority(STORE),
                Permission.EMPLOYEE_MANAGE.authority(), Permission.REPORT_EXPORT.authority(STORE))));

        assertStepUp(() -> policy.require(Permission.CASH_CLOSING_APPROVE, STORE),
                StepUpRequiredException.ACCOUNT_REQUIRED);
        assertStepUp(() -> policy.require(Permission.EMPLOYEE_MANAGE), StepUpRequiredException.ACCOUNT_REQUIRED);
        assertStepUp(() -> policy.requireAny(STORE, Permission.CASH_VIEW, Permission.CASH_CLOSING_APPROVE),
                StepUpRequiredException.ACCOUNT_REQUIRED);
        assertStepUp(() -> policy.scopeOf(Permission.REPORT_EXPORT), StepUpRequiredException.ACCOUNT_REQUIRED);
        assertStepUp(() -> policy.requireInAnyStore(Permission.REPORT_EXPORT),
                StepUpRequiredException.ACCOUNT_REQUIRED);
    }

    @Test
    void anEmployeeKeepsNonSensitiveAccessWhenEnforced() {
        AccessPolicy policy = policy(NEVER);
        actAs(Actor.employee(EMPLOYEE, TERMINAL, STORE, Set.of(Permission.CASH_VIEW.authority(STORE),
                Permission.CASH_CLOSING_APPROVE.authority(STORE))));

        assertThatCode(() -> policy.require(Permission.CASH_VIEW, STORE)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireAny(STORE, Permission.CASH_VIEW, Permission.CASH_CLOSING_APPROVE))
                .doesNotThrowAnyException();
    }

    @Test
    void anEmployeeIsNotAskedWhenStepUpIsNotEnforced() {
        AccessPolicy policy = policy(AssuranceState.NOT_ENFORCED);
        actAs(Actor.employee(EMPLOYEE, TERMINAL, STORE, Set.of(Permission.CASH_CLOSING_APPROVE.authority(STORE))));

        assertThatCode(() -> policy.require(Permission.CASH_CLOSING_APPROVE, STORE)).doesNotThrowAnyException();
    }

    @Test
    void thePosGateNeverAsksEvenForASensitivePermission() {
        AccessPolicy policy = policy(NEVER);
        actAs(Actor.employee(EMPLOYEE, TERMINAL, STORE, Set.of(Permission.CASH_CLOSING_APPROVE.authority(STORE))));

        assertThatCode(() -> policy.requirePos(Permission.CASH_CLOSING_APPROVE)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requirePos(Permission.CASH_CLOSING_APPROVE, STORE)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requirePosAny(Permission.CASH_CLOSING_APPROVE)).doesNotThrowAnyException();
        assertThat(lookups).hasValue(0);
    }

    @Test
    void anAssuranceStateNeedsAMaxAge() {
        assertThatThrownBy(() -> new AssuranceState(true, true, NOW, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aMissingPermissionIsStillThePlainDenialFirst() {
        AccessPolicy policy = policy(NEVER);
        actAsAccount(Permission.CATALOG_VIEW.authority());

        assertThatThrownBy(() -> policy.require(Permission.USER_MANAGE))
                .isInstanceOf(AccessDeniedException.class)
                .isNotInstanceOf(StepUpRequiredException.class);
        assertThatThrownBy(() -> policy.require(Permission.CASH_ADJUST, STORE))
                .isNotInstanceOf(StepUpRequiredException.class);
        assertThatThrownBy(() -> policy.requireAny(STORE, Permission.CASH_CLOSING_APPROVE, Permission.CASH_ADJUST))
                .isNotInstanceOf(StepUpRequiredException.class);
        assertThatThrownBy(() -> policy.scopeOf(Permission.REPORT_EXPORT))
                .isInstanceOf(AccessDeniedException.class)
                .isNotInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void aStoreScopedSensitiveGrantNeedsAStepUp() {
        AccessPolicy policy = policy(STALE);
        actAsAccount(Permission.CASH_ADJUST.authority(STORE));

        assertStepUp(() -> policy.require(Permission.CASH_ADJUST, STORE), StepUpRequiredException.STEP_UP_REQUIRED);
    }

    @Test
    void requireAnyPassesWhenANonSensitivePermissionAlsoGrants() {
        AccessPolicy policy = policy(NEVER);
        actAsAccount(Permission.CASH_VIEW.authority(STORE), Permission.CASH_CLOSING_APPROVE.authority(STORE));

        assertThatCode(() -> policy.requireAny(STORE, Permission.CASH_VIEW, Permission.CASH_CLOSING_APPROVE))
                .doesNotThrowAnyException();
    }

    @Test
    void requireAnyNeedsAStepUpWhenOnlySensitivePermissionsGrant() {
        AccessPolicy policy = policy(NEVER);
        actAsAccount(Permission.CASH_CLOSING_APPROVE.authority(STORE));

        assertStepUp(() -> policy.requireAny(STORE, Permission.CASH_VIEW, Permission.CASH_CLOSING_APPROVE),
                StepUpRequiredException.STEP_UP_REQUIRED);
    }

    @Test
    void scopeOfAndRequireInAnyStoreNeedAStepUpForASensitivePermission() {
        AccessPolicy policy = policy(STALE);
        actAsAccount(Permission.REPORT_EXPORT.authority(STORE));

        assertStepUp(() -> policy.scopeOf(Permission.REPORT_EXPORT), StepUpRequiredException.STEP_UP_REQUIRED);
        assertStepUp(() -> policy.requireInAnyStore(Permission.REPORT_EXPORT),
                StepUpRequiredException.STEP_UP_REQUIRED);
    }

    @Test
    void scopeOfStillReturnsTheScopeWhenTheFactorIsFresh() {
        AccessPolicy policy = policy(FRESH);
        actAsAccount(Permission.REPORT_EXPORT.authority(STORE));

        assertThat(policy.scopeOf(Permission.REPORT_EXPORT)).isEqualTo(new AccessScope(false, Set.of(STORE)));
    }

    @Test
    void theAssuranceIsAskedOncePerRequest() {
        AccessPolicy policy = policy(FRESH);
        actAsAccount(Permission.USER_MANAGE.authority(), Permission.ROLE_MANAGE.authority());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        policy.require(Permission.USER_MANAGE);
        policy.require(Permission.ROLE_MANAGE);
        policy.scopeOf(Permission.USER_MANAGE);

        assertThat(lookups).hasValue(1);
    }

    private AccessPolicy policy(AssuranceState state) {
        AuthenticationAssurance counting = () -> {
            lookups.incrementAndGet();
            return state;
        };
        StoreActivity allActive = storeId -> true;
        return new AccessPolicy(new StaticListableBeanFactory(Map.of("stores", allActive))
                .getBeanProvider(StoreActivity.class),
                new StaticListableBeanFactory(Map.of("assurance", counting))
                        .getBeanProvider(AuthenticationAssurance.class),
                clock());
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static AssuranceState enrolled(Instant secondFactorAt) {
        return new AssuranceState(true, true, secondFactorAt, MAX_AGE);
    }

    private static void actAsAccount(String... authorities) {
        actAs(Actor.account(ACCOUNT, Arrays.stream(authorities).collect(Collectors.toSet())));
    }

    private static void actAs(Actor actor) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
    }

    private static void assertStepUp(Executable call, String code) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(StepUpRequiredException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
