package com.xavier.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import com.xavier.backend.tenancy.exception.IdentityProviderUnavailableException;
import com.xavier.backend.tenancy.model.dto.Membership;
import com.xavier.backend.tenancy.model.dto.MembershipGrant;
import com.xavier.backend.tenancy.model.dto.MembershipSummary;
import com.xavier.backend.tenancy.model.dto.TenantStatus;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * D177/D181/D185 (security rule 9): the tenancy SECURITY DEFINER functions, through {@link TenancyApi} as the
 * application role, which holds no privilege on any tenancy table. A membership resolves only for its own (issuer,
 * subject), kinds and tenant, with the same empty answer for a foreign and a random tenant; writes act on the bound
 * tenant only (another tenant {@code 42501}, none {@code RS003}); a support grant lasts at most 24 h; only ACTIVE
 * tenants are iterated; activation leaves PROVISIONING only; an unconfigured identity provider fails closed.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@StrictTenantScope
class TenancyFunctionsTest {

    static final String ISSUER = "https://auth.example/realms/restaurantpro";
    static final Set<MembershipKind> TENANT_REALM = Set.of(MembershipKind.ACCOUNT, MembershipKind.TERMINAL,
            MembershipKind.BRIDGE);

    @Autowired
    TenancyApi tenancy;
    @Autowired
    TenantScopes tenants;

    @BeforeAll
    static void tenantsExist() {
        TenantTestSupport.ensureTenants();
    }

    @Test
    void aMembershipResolvesOnlyForItsSubjectKindsAndTenant() {
        String subject = UUID.randomUUID().toString();
        UUID account = UUID.randomUUID();
        grantIn(TenantTestSupport.A, subject, MembershipKind.ACCOUNT, account, null);

        assertThat(tenancy.resolve(ISSUER, subject, null, TENANT_REALM)).contains(new Membership(TenantTestSupport.A,
                MembershipKind.ACCOUNT, account, TenantStatus.PROVISIONING));
        assertThat(tenancy.resolve(ISSUER, subject, TenantTestSupport.B, TENANT_REALM)).as("a foreign tenant")
                .isEqualTo(tenancy.resolve(ISSUER, subject, UUID.randomUUID(), TENANT_REALM)).isEmpty();
        assertThat(tenancy.resolve(ISSUER, subject, null, Set.of(MembershipKind.SUPPORT))).as("another kind")
                .isEmpty();
        assertThat(tenancy.resolve("https://other.example/realms/x", subject, null, TENANT_REALM))
                .as("another issuer").isEmpty();
    }

    @Test
    void severalMembershipsNeedASelectionAndAreListedOnlyToTheirSubject() {
        String subject = UUID.randomUUID().toString();
        grantIn(TenantTestSupport.A, subject, MembershipKind.ACCOUNT, UUID.randomUUID(), null);
        grantIn(TenantTestSupport.B, subject, MembershipKind.ACCOUNT, UUID.randomUUID(), null);

        assertThat(tenancy.memberships(ISSUER, subject)).extracting(Membership::tenantId)
                .containsExactlyInAnyOrder(TenantTestSupport.A, TenantTestSupport.B);
        assertThat(tenancy.memberships(ISSUER, UUID.randomUUID().toString())).isEmpty();
        assertThatThrownBy(() -> tenancy.resolve(ISSUER, subject, null, TENANT_REALM))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tenancy.resolve(ISSUER, subject, TenantTestSupport.B, TENANT_REALM)).map(Membership::tenantId)
                .contains(TenantTestSupport.B);
    }

    @Test
    void membershipSummariesListOnlyTheSubjectsActiveAndCurrentlyValidMemberships() {
        String subject = UUID.randomUUID().toString();
        UUID expiredIn = UUID.randomUUID();
        TenantTestSupport.ensureTenant(expiredIn, "summaries-" + expiredIn, "PROVISIONING");
        String orgId = "org-" + UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("UPDATE tenancy.tenant SET keycloak_org_id = ? WHERE id = ?").param(orgId)
                .param(TenantTestSupport.A).update();
        grantIn(TenantTestSupport.A, subject, MembershipKind.ACCOUNT, UUID.randomUUID(), null);
        UUID revoked = grantIn(TenantTestSupport.B, subject, MembershipKind.ACCOUNT, UUID.randomUUID(), null);
        TenantTestSupport.inTenant(tenants, TenantTestSupport.B, () -> tenancy.revoke(revoked));
        UUID expired = grantIn(expiredIn, subject, MembershipKind.ACCOUNT, UUID.randomUUID(), Duration.ofHours(1));
        OwnerDatabase.shared().jdbc().sql("UPDATE tenancy.tenant_membership SET valid_from = now() - interval '2 hours', "
                + "valid_until = now() - interval '1 hour' WHERE id = ?").param(expired).update();
        grantIn(TenantTestSupport.B, UUID.randomUUID().toString(), MembershipKind.ACCOUNT, UUID.randomUUID(), null);

        assertThat(tenancy.membershipSummaries(ISSUER, subject)).containsExactly(new MembershipSummary(
                TenantTestSupport.A, "tenant-a", "tenant-a", MembershipKind.ACCOUNT, orgId,
                TenantStatus.PROVISIONING));
        assertThat(tenancy.membershipSummaries("https://other.example/realms/x", subject)).as("another issuer")
                .isEmpty();
    }

    @Test
    void onlyTheBoundTenantGrantsAndRevokesAndARevokedMembershipNoLongerResolves() {
        String subject = UUID.randomUUID().toString();
        UUID inA = grantIn(TenantTestSupport.A, subject, MembershipKind.ACCOUNT, UUID.randomUUID(), null);

        assertState(() -> TenantTestSupport.inTenant(tenants, TenantTestSupport.B, () -> tenancy.revoke(inA)),
                "42501");
        assertThat(tenancy.resolve(ISSUER, subject, null, TENANT_REALM)).as("B could not revoke A's").isPresent();
        AppRoleSession.assertRefused(() -> tenancy.revoke(inA), StoreScopeViolations.Kind.TENANT_MISSING);
        try (AppRoleSession app = AppRoleSession.open().tenant(TenantTestSupport.A)) {
            assertState(() -> app.jdbc().sql("SELECT tenancy.grant_membership(?, ?, ?, 'ACCOUNT', ?, NULL::interval)")
                    .param(TenantTestSupport.B).param(ISSUER).param(UUID.randomUUID().toString())
                    .param(UUID.randomUUID()).query().listOfRows(), "42501");
        }

        TenantTestSupport.inTenant(tenants, TenantTestSupport.A, () -> tenancy.revoke(inA));

        assertThat(tenancy.resolve(ISSUER, subject, null, TENANT_REALM)).isEmpty();
    }

    @Test
    void aSupportGrantIsTimeBoxedToAtMostADay() {
        String subject = UUID.randomUUID().toString();

        assertState(() -> grantIn(TenantTestSupport.A, subject, MembershipKind.SUPPORT, UUID.randomUUID(), null),
                "23514");
        assertState(() -> grantIn(TenantTestSupport.A, subject, MembershipKind.SUPPORT, UUID.randomUUID(),
                Duration.ofHours(25)), "23514");
        grantIn(TenantTestSupport.A, subject, MembershipKind.SUPPORT, UUID.randomUUID(), Duration.ofHours(8));

        assertThat(tenancy.resolve(ISSUER, subject, null, Set.of(MembershipKind.SUPPORT))).isPresent();
    }

    @Test
    void activationLeavesProvisioningOnlyAndOnlyActiveTenantsAreIterated() {
        UUID provisioning = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        TenantTestSupport.ensureTenant(provisioning, "functions-" + provisioning, "PROVISIONING");
        TenantTestSupport.ensureTenant(suspended, "functions-" + suspended, "SUSPENDED");
        assertThat(tenancy.activeTenantIds()).contains(TenantTestSupport.BOOTSTRAP)
                .doesNotContain(provisioning, suspended);

        try {
            TenantTestSupport.inTenant(tenants, provisioning, () -> {
                tenancy.activate(provisioning);
                tenancy.activate(provisioning); // idempotent
            });

            assertThat(tenancy.activeTenantIds()).contains(provisioning);
            assertThat(TenantTestSupport.inTenant(tenants, provisioning, () -> tenancy.status(provisioning)))
                    .isEqualTo(TenantStatus.ACTIVE);
            assertState(() -> TenantTestSupport.inTenant(tenants, suspended, () -> tenancy.activate(suspended)),
                    "P0001");
            assertState(() -> TenantTestSupport.inTenant(tenants, provisioning, () -> tenancy.activate(suspended)),
                    "42501");
            assertState(() -> TenantTestSupport.inTenant(tenants, provisioning, () -> tenancy.status(suspended)),
                    "42501");
        } finally {
            // no ACTIVE test tenant is left for the other suites' jobs
            TenantTestSupport.ensureTenant(provisioning, "functions-" + provisioning, "CLOSED");
        }
    }

    @Test
    void platformWritesAreRefusedInATenantBoundTransaction() {
        String code = "bound-" + UUID.randomUUID().toString().substring(0, 8);
        try (AppRoleSession app = AppRoleSession.open().tenant(TenantTestSupport.A)) {
            assertState(() -> app.jdbc().sql("SELECT * FROM tenancy.create_tenant(?, 'Bound Lda', NULL, 'op', NULL)")
                    .param(code).query().listOfRows(), "TN004");
            assertState(() -> app.jdbc().sql("SELECT * FROM tenancy.set_tenant_status(?, 'SUSPENDED', 'op', 'r')")
                    .param(TenantTestSupport.B).query().listOfRows(), "TN004");
            assertState(() -> app.jdbc().sql("SELECT tenancy.record_platform_event(?, 'op', 'TEST_EVENT', NULL)")
                    .param(TenantTestSupport.B).query().listOfRows(), "TN004");
        }
        try (AppRoleSession app = AppRoleSession.open().noTenant()) {
            assertThat(app.jdbc().sql("SELECT status FROM tenancy.create_tenant(?, 'Bound Lda', NULL, 'op', NULL)")
                    .param(code).query(String.class).single()).as("unbound: a platform transaction")
                    .isEqualTo("PROVISIONING");
        }
    }

    @Test
    void theApplicationRoleReadsNoTenancyTableDirectly() {
        try (AppRoleSession app = AppRoleSession.open().tenant(TenantTestSupport.A)) {
            assertState(() -> app.jdbc().sql("SELECT count(*) FROM tenancy.tenant").query(Long.class).single(),
                    "42501");
            assertState(() -> app.jdbc().sql("SELECT count(*) FROM tenancy.tenant_membership").query(Long.class)
                    .single(), "42501");
        }
    }

    @Test
    void anUnconfiguredIdentityProviderFailsClosed() {
        assertThatThrownBy(() -> tenancy.idp().createOrganization("acme", "Acme"))
                .isInstanceOfSatisfying(IdentityProviderUnavailableException.class,
                        error -> assertThat(error.code()).isEqualTo("IDP_UNAVAILABLE"));
    }

    private UUID grantIn(UUID tenant, String subject, MembershipKind kind, UUID principal, Duration validFor) {
        return TenantTestSupport.inTenant(tenants, tenant, () -> tenancy.grant(new MembershipGrant(ISSUER, subject,
                kind, principal, validFor)));
    }

    private static void assertState(ThrowingCallable call, String sqlState) {
        assertThatThrownBy(call).satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo(sqlState));
    }
}
