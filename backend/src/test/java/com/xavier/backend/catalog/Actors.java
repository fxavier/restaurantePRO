package com.xavier.backend.catalog;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Per-request actors for MockMvc when the store id is only known at run time ({@link WithActor} needs a
 * constant). Builds the same principal and authority format as {@code WithActor}.
 */
public final class Actors {

    public static final UUID USER_ID = UUID.fromString(WithActor.DEFAULT_USER_ID);

    private Actors() {
    }

    /** Installation-wide permissions. */
    public static RequestPostProcessor admin(Permission... permissions) {
        return as(null, Arrays.stream(permissions).map(Permission::authority).collect(Collectors.toSet()));
    }

    /** Permissions granted only in {@code storeId}. */
    public static RequestPostProcessor inStore(UUID storeId, Permission... permissions) {
        return as(storeId, Arrays.stream(permissions).map(p -> p.authority(storeId)).collect(Collectors.toSet()));
    }

    private static RequestPostProcessor as(UUID storeId, Set<String> authorities) {
        Actor actor = new Actor(ActorKind.ACCOUNT, USER_ID, null, null, storeId, authorities);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
