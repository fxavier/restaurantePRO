package com.xavier.backend.tenancy;

import com.xavier.backend.shared.tenancy.ActiveTenants;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.tenancy.model.dto.Membership;
import com.xavier.backend.tenancy.model.dto.MembershipGrant;
import com.xavier.backend.tenancy.model.dto.MembershipSummary;
import com.xavier.backend.tenancy.model.dto.TenantStatus;
import com.xavier.backend.tenancy.model.event.MembershipRevoked;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code identity} and {@code config} may ask the tenancy module (D181), each call through one SECURITY DEFINER
 * function of V15001: the application role holds no privilege on {@code tenancy.*} (D177). Reads never list a tenant
 * the subject does not belong to; writes act on the bound tenant only (another tenant → {@code 42501}, none →
 * {@code RS003}).
 */
@Service
public class TenancyApi {

    private static final String[] ALL_KINDS = {"ACCOUNT", "TERMINAL", "BRIDGE", "SUPPORT"};

    private final JdbcClient jdbc;
    private final TenantScopes tenants;
    private final ActiveTenants activeTenants;
    private final IdentityProviderAdmin identityProvider;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TenancyApi(JdbcClient jdbc, TenantScopes tenants, ActiveTenants activeTenants,
               IdentityProviderAdmin identityProvider, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.activeTenants = activeTenants;
        this.identityProvider = identityProvider;
        this.events = events;
        this.clock = clock;
    }

    /** Every ACTIVE membership valid now of {@code (issuer, subject)}, of any kind ({@code GET /auth/tenants}). */
    @Transactional(readOnly = true)
    public List<Membership> memberships(String issuer, String subject) {
        return resolveAll(issuer, subject, null, ALL_KINDS);
    }

    /**
     * Every ACTIVE membership valid now of {@code (issuer, subject)} with its tenant's code, name and Keycloak
     * organization; never a tenant the subject does not belong to.
     */
    @Transactional(readOnly = true)
    public List<MembershipSummary> membershipSummaries(String issuer, String subject) {
        return jdbc.sql("SELECT tenant_id, code, legal_name, kind, keycloak_org_id, tenant_status "
                        + "FROM tenancy.list_memberships(:issuer, :subject)")
                .param("issuer", issuer).param("subject", subject)
                .query((rs, row) -> new MembershipSummary(rs.getObject("tenant_id", UUID.class), rs.getString("code"),
                        rs.getString("legal_name"), MembershipKind.valueOf(rs.getString("kind")),
                        rs.getString("keycloak_org_id"), TenantStatus.valueOf(rs.getString("tenant_status"))))
                .list();
    }

    /**
     * The one membership of {@code (issuer, subject)} of one of {@code kinds} (the issuer's kinds, D177 amended), in
     * {@code requested} when given.
     *
     * @return empty when there is none: the same answer for a foreign tenant and a random one (no oracle)
     * @throws MembershipSelectionRequiredException when several match and none was requested (the caller answers
     *                                               {@code TENANT_SELECTION_REQUIRED}, listing nothing)
     */
    @Transactional(readOnly = true)
    public Optional<Membership> resolve(String issuer, String subject, UUID requested, Set<MembershipKind> kinds) {
        List<Membership> found = resolveAll(issuer, subject, requested,
                kinds.stream().map(MembershipKind::name).toArray(String[]::new));
        if (found.size() > 1) {
            throw new MembershipSelectionRequiredException();
        }
        return found.stream().findFirst();
    }

    /** Grants (or reactivates) a membership in the bound tenant, valid from now by the database's clock. @return its id */
    @Transactional
    public UUID grant(MembershipGrant grant) {
        return jdbc.sql("SELECT tenancy.grant_membership(:tenant, :issuer, :subject, :kind, :principal, "
                        + "CAST(:validForMicros AS bigint) * interval '1 microsecond')")
                .param("tenant", tenants.requireTenant()).param("issuer", grant.issuer())
                .param("subject", grant.subject()).param("kind", grant.kind().name())
                .param("principal", grant.principalId())
                .param("validForMicros", grant.validFor() == null ? null
                        : TimeUnit.NANOSECONDS.toMicros(grant.validFor().toNanos()))
                .query(UUID.class).single();
    }

    /** Revokes a membership of the bound tenant; its subject is refused on its next request. */
    @Transactional
    public void revoke(UUID membershipId) {
        jdbc.sql("SELECT tenancy.revoke_membership(:id)").param("id", membershipId).query().listOfRows();
        events.publishEvent(new MembershipRevoked(tenants.requireTenant(), membershipId, clock.instant()));
    }

    /** The bound tenant's status ({@code tenantId} must be the bound tenant). */
    @Transactional(readOnly = true)
    public TenantStatus status(UUID tenantId) {
        return TenantStatus.valueOf(jdbc.sql("SELECT tenancy.tenant_status(:tenant)").param("tenant", tenantId)
                .query(String.class).single());
    }

    /** The last step of onboarding: the bound tenant leaves PROVISIONING (idempotent once ACTIVE). */
    @Transactional
    public void activate(UUID tenantId) {
        jdbc.sql("SELECT tenancy.activate_tenant(:tenant)").param("tenant", tenantId).query().listOfRows();
    }

    /** The ACTIVE tenants (ids only) a job iterates (D185). */
    public List<UUID> activeTenantIds() {
        return activeTenants.ids();
    }

    /** The identity provider's admin port (D181, D182). */
    public IdentityProviderAdmin idp() {
        return identityProvider;
    }

    private List<Membership> resolveAll(String issuer, String subject, UUID requested, String[] kinds) {
        return jdbc.sql("SELECT tenant_id, kind, principal_id, tenant_status "
                        + "FROM tenancy.resolve_membership(:issuer, :subject, CAST(:requested AS uuid), "
                        + "CAST(:kinds AS text[]))")
                .param("issuer", issuer).param("subject", subject).param("requested", requested)
                .param("kinds", "{" + String.join(",", Arrays.asList(kinds)) + "}")
                .query((rs, row) -> new Membership(rs.getObject("tenant_id", UUID.class),
                        MembershipKind.valueOf(rs.getString("kind")), rs.getObject("principal_id", UUID.class),
                        TenantStatus.valueOf(rs.getString("tenant_status"))))
                .list();
    }
}
