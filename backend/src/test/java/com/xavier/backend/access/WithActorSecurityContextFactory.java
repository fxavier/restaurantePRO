package com.xavier.backend.access;

import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/**
 * Builds the security context the request pipeline builds for a resolved caller ({@link ActorAuthentication}): the
 * actor, its tenant, and, for an account, a second factor proved now on the context's {@link Clock} ({@code acr 2},
 * D184), so a sensitive permission works unless the test asks otherwise ({@link WithActor#acr()}).
 */
final class WithActorSecurityContextFactory implements WithSecurityContextFactory<WithActor> {

    private final ObjectProvider<Clock> clock;

    @Autowired
    WithActorSecurityContextFactory(ObjectProvider<Clock> clock) {
        this.clock = clock;
    }

    WithActorSecurityContextFactory() {
        this.clock = null;
    }

    @Override
    public SecurityContext createSecurityContext(WithActor annotation) {
        UUID storeId = uuidOrNull(annotation.storeId());
        Set<String> authorities = Arrays.stream(annotation.permissions())
                .map(p -> storeId == null ? p.authority() : p.authority(storeId))
                .collect(Collectors.toUnmodifiableSet());
        UUID employeeId = uuidOrNull(annotation.employeeId());
        // an employee id makes it a POS session, whose principal id is the employee (D38)
        Actor actor = employeeId == null
                ? new Actor(ActorKind.ACCOUNT, UUID.fromString(annotation.userId()), null,
                        uuidOrNull(annotation.terminalId()), storeId, authorities)
                : Actor.employee(employeeId, uuidOrNull(annotation.terminalId()), storeId, authorities);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        // the tenant of the actor's membership (D177), as config.security's resolution sets it
        UUID tenantId = annotation.tenantId().isBlank() ? TenantTestSupport.BOOTSTRAP
                : UUID.fromString(annotation.tenantId());
        boolean account = actor.kind() == ActorKind.ACCOUNT;
        Clock now = clock == null ? null : clock.getIfAvailable();
        Assurance assurance = account
                ? new Assurance(annotation.acr(), now == null ? Instant.now() : now.instant()) : Assurance.NONE;
        context.setAuthentication(new ActorAuthentication(actor, new TenantContext(tenantId,
                account ? MembershipKind.ACCOUNT : MembershipKind.TERMINAL,
                account || actor.terminalId() == null ? actor.userId() : actor.terminalId(), "test:with-actor"),
                assurance));
        return context;
    }

    private static UUID uuidOrNull(String value) {
        return value.isBlank() ? null : UUID.fromString(value);
    }
}
