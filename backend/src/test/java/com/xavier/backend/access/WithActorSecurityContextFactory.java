package com.xavier.backend.access;

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
        Actor actor = new Actor(UUID.fromString(annotation.userId()), uuidOrNull(annotation.employeeId()),
                uuidOrNull(annotation.terminalId()), storeId, authorities);

        List<SimpleGrantedAuthority> granted = authorities.stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, granted));
        return context;
    }

    private static UUID uuidOrNull(String value) {
        return value.isBlank() ? null : UUID.fromString(value);
    }
}
