package com.xavier.backend.fiscal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Per-request actors for MockMvc (same principal and authority format as WithActor). */
public final class FiscalActors {

    public static final UUID USER_ID = UUID.fromString(WithActor.DEFAULT_USER_ID);

    private FiscalActors() {
    }

    /** BackOffice account with installation-wide permissions. */
    public static RequestPostProcessor admin(Permission... permissions) {
        return authentication(account(null, Arrays.stream(permissions).map(Permission::authority)
                .collect(Collectors.toSet())));
    }

    /** BackOffice account with permissions in {@code storeId} only. */
    public static RequestPostProcessor inStore(UUID storeId, Permission... permissions) {
        return authentication(account(storeId, Arrays.stream(permissions).map(p -> p.authority(storeId))
                .collect(Collectors.toSet())));
    }

    /** POS employee session on {@code terminalId} in {@code storeId}. */
    public static RequestPostProcessor pos(UUID storeId, UUID employeeId, UUID terminalId,
                                           Permission... permissions) {
        return authentication(employee(storeId, employeeId, terminalId, permissions));
    }

    public static Authentication employee(UUID storeId, UUID employeeId, UUID terminalId,
                                          Permission... permissions) {
        Set<String> authorities = Arrays.stream(permissions).map(p -> p.authority(storeId))
                .collect(Collectors.toSet());
        return token(Actor.employee(employeeId, terminalId, storeId, authorities), authorities);
    }

    private static Authentication account(UUID storeId, Set<String> authorities) {
        return token(new Actor(ActorKind.ACCOUNT, USER_ID, null, null, storeId, authorities), authorities);
    }

    private static Authentication token(Actor actor, Set<String> authorities) {
        return UsernamePasswordAuthenticationToken.authenticated(actor, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
    }
}
