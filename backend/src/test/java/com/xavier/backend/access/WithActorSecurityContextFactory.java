package com.xavier.backend.access;

import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/** Builds the same kind of security context S2's login will: principal = {@link Actor}. */
final class WithActorSecurityContextFactory implements WithSecurityContextFactory<WithActor> {

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

        List<SimpleGrantedAuthority> granted = authorities.stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(actor,
                null, granted);
        // the tenant of the actor's membership (D177): what config.security's resolution will set from S72 on
        UUID tenantId = annotation.tenantId().isBlank() ? TenantTestSupport.BOOTSTRAP
                : UUID.fromString(annotation.tenantId());
        authentication.setDetails(new TenantContext(tenantId, MembershipKind.ACCOUNT, actor.userId(),
                "test:with-actor"));
        context.setAuthentication(authentication);
        return context;
    }

    private static UUID uuidOrNull(String value) {
        return value.isBlank() ? null : UUID.fromString(value);
    }
}
