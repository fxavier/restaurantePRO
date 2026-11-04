package com.xavier.backend.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Per-request actors for MockMvc when the store id is only known at run time (same format as WithActor). */
public final class DiningActors {

    public static final UUID USER_ID = UUID.fromString(WithActor.DEFAULT_USER_ID);
    public static final UUID TERMINAL_ID = UUID.fromString("0190f000-0000-7000-8000-0000000000d8");

    private DiningActors() {
    }

    public static RequestPostProcessor admin(Permission... permissions) {
        return as(null, Arrays.stream(permissions).map(Permission::authority).collect(Collectors.toSet()));
    }

    public static RequestPostProcessor inStore(UUID storeId, Permission... permissions) {
        return as(storeId, Arrays.stream(permissions).map(p -> p.authority(storeId)).collect(Collectors.toSet()));
    }

    /** A POS session (EMPLOYEE on a terminal) of {@code storeId}, for MockMvc. */
    public static RequestPostProcessor pos(UUID storeId, UUID employeeId, Permission... permissions) {
        Actor actor = Actor.employee(employeeId, TERMINAL_ID, storeId,
                Arrays.stream(permissions).map(p -> p.authority(storeId)).collect(Collectors.toSet()));
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.authorities().stream().map(SimpleGrantedAuthority::new).toList()));
    }

    /**
     * Runs {@code action} on this thread as a POS employee of {@code storeId}: what a POS request of the calling
     * module (orders) has in its security context when it calls {@code DiningApi}.
     */
    public static <T> T runAsPos(UUID storeId, UUID employeeId, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                Actor.employee(employeeId, TERMINAL_ID, storeId, Set.of()), null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static RequestPostProcessor as(UUID storeId, Set<String> authorities) {
        Actor actor = new Actor(ActorKind.ACCOUNT, USER_ID, null, null, storeId, authorities);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
