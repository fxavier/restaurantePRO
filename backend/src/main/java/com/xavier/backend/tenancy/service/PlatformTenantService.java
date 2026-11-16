package com.xavier.backend.tenancy.service;

import com.xavier.backend.tenancy.IdentityProviderAdmin;
import com.xavier.backend.tenancy.exception.IllegalTenantStateException;
import com.xavier.backend.tenancy.exception.PlatformForbiddenException;
import com.xavier.backend.tenancy.exception.TenantCodeTakenException;
import com.xavier.backend.tenancy.exception.TenantNotFoundException;
import com.xavier.backend.tenancy.model.dto.OnboardTenantRequest;
import com.xavier.backend.tenancy.model.dto.PlatformTenantView;
import com.xavier.backend.tenancy.model.dto.TenantStatus;
import com.xavier.backend.tenancy.model.event.TenantClosed;
import com.xavier.backend.tenancy.model.event.TenantProvisioned;
import com.xavier.backend.tenancy.model.event.TenantReactivated;
import com.xavier.backend.tenancy.model.event.TenantSuspended;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The platform operator's tenant lifecycle (D181 as amended). It touches {@code tenancy.*} only, through the platform
 * SECURITY DEFINER functions of V15002, and never reads business data. Onboarding is idempotent by the tenant code
 * and follows D48: the tenant is persisted (PROVISIONING), then the Keycloak organization is created outside any
 * transaction, then its id is recorded, then {@code TenantProvisioned} starts the event chain (organization →
 * {@code CompanyProvisioned} → identity, which activates the tenant). A failure between steps leaves a PROVISIONING
 * tenant that the same command completes when replayed. Status changes publish their event only when they change the
 * status, so a repeated suspension is a no-op.
 */
@Service
public class PlatformTenantService {

    private static final String TENANT_CODE_TAKEN = "TN001";
    private static final String ILLEGAL_TENANT_STATE = "TN002";
    private static final String NO_SUCH_TENANT = "TN003";
    private static final String TENANT_BOUND = "TN004";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final IdentityProviderAdmin identityProvider;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PlatformTenantService(JdbcClient jdbc, PlatformTransactionManager transactions,
                          IdentityProviderAdmin identityProvider, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.identityProvider = identityProvider;
        this.events = events;
        this.clock = clock;
    }

    public PlatformTenantView onboard(OnboardTenantRequest request, String operator) {
        Created tenant = inTransaction(() -> createTenant(request, null, operator));
        if (tenant.organizationId() == null) {
            String organizationId = identityProvider.createOrganization(request.code(), request.legalName());
            tenant = inTransaction(() -> createTenant(request, organizationId, operator));
        }
        if (tenant.status() == TenantStatus.PROVISIONING) {
            UUID id = tenant.id();
            inTransaction(() -> {
                jdbc.sql("SELECT tenancy.record_platform_event(:tenant, :operator, 'ONBOARDING_PUBLISHED', NULL)")
                        .param("tenant", id).param("operator", operator).query().listOfRows();
                events.publishEvent(new TenantProvisioned(id, request.legalName(), request.taxId(),
                        request.country(), request.currency(), request.firstStoreName(), request.adminEmail()));
                return null;
            });
        }
        return new PlatformTenantView(tenant.id(), request.code(), tenant.status());
    }

    public PlatformTenantView suspend(UUID tenantId, String reason, String operator) {
        return change(tenantId, TenantStatus.SUSPENDED, reason, operator, TenantSuspended::new);
    }

    public PlatformTenantView reactivate(UUID tenantId, String reason, String operator) {
        return change(tenantId, TenantStatus.ACTIVE, reason, operator, TenantReactivated::new);
    }

    public PlatformTenantView close(UUID tenantId, String reason, String operator) {
        return change(tenantId, TenantStatus.CLOSED, reason, operator, TenantClosed::new);
    }

    private PlatformTenantView change(UUID tenantId, TenantStatus target, String reason, String operator,
                                      BiFunction<UUID, Instant, Object> event) {
        return inTransaction(() -> {
            Changed changed = jdbc.sql("SELECT code, status, changed "
                            + "FROM tenancy.set_tenant_status(:tenant, :status, :operator, :reason)")
                    .param("tenant", tenantId).param("status", target.name()).param("operator", operator)
                    .param("reason", reason)
                    .query((rs, row) -> new Changed(rs.getString("code"), TenantStatus.valueOf(rs.getString("status")),
                            rs.getBoolean("changed")))
                    .single();
            if (changed.changed()) {
                events.publishEvent(event.apply(tenantId, clock.instant()));
            }
            return new PlatformTenantView(tenantId, changed.code(), changed.status());
        });
    }

    private Created createTenant(OnboardTenantRequest request, String organizationId, String operator) {
        return jdbc.sql("SELECT tenant_id, status, keycloak_org_id "
                        + "FROM tenancy.create_tenant(:code, :legalName, :organization, :operator)")
                .param("code", request.code()).param("legalName", request.legalName())
                .param("organization", organizationId).param("operator", operator)
                .query((rs, row) -> new Created(rs.getObject("tenant_id", UUID.class),
                        TenantStatus.valueOf(rs.getString("status")), rs.getString("keycloak_org_id")))
                .single();
    }

    /** One short transaction (no external call inside, D48), its refusals turned into the platform's errors. */
    private <T> T inTransaction(Supplier<T> work) {
        try {
            return transaction.execute(status -> work.get());
        } catch (DataAccessException e) {
            throw switch (sqlState(e)) {
                case TENANT_CODE_TAKEN -> new TenantCodeTakenException();
                case ILLEGAL_TENANT_STATE -> new IllegalTenantStateException();
                case NO_SUCH_TENANT -> new TenantNotFoundException();
                case TENANT_BOUND -> new PlatformForbiddenException();
                case null, default -> e;
            };
        }
    }

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private record Created(UUID id, TenantStatus status, String organizationId) {
    }

    private record Changed(String code, TenantStatus status, boolean changed) {
    }
}
