package com.xavier.backend.orders;

import com.xavier.backend.access.TestAuthentications;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** POS sessions (D39) for MockMvc and for direct facade calls; each terminal of a test is its own id. */
public final class OrdersActors {

    private OrdersActors() {
    }

    /** An employee on {@code terminal} of {@code store}, with {@code permissions} in that store. */
    public static RequestPostProcessor pos(UUID store, UUID employee, UUID terminal, Permission... permissions) {
        Actor actor = actor(store, employee, terminal, permissions);
        return authentication(TestAuthentications.of(actor));
    }

    /** A BackOffice account with installation-wide {@code permissions}: never a POS session. */
    public static RequestPostProcessor backOffice(Permission... permissions) {
        Actor actor = Actor.account(UUID.randomUUID(),
                Arrays.stream(permissions).map(Permission::authority).collect(Collectors.toSet()));
        return authentication(TestAuthentications.of(actor));
    }

    /**
     * Runs {@code action} on this thread as that POS session (what settlement's request has when it calls). The
     * session holds settlement's {@code PAYMENT_COLLECT} there, so its store scope is the store (D164: a session
     * holding no authority sees no store rows).
     */
    public static <T> T runAsPos(UUID store, UUID employee, UUID terminal, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(TestAuthentications.of(actor(store, employee, terminal, Permission.PAYMENT_COLLECT)));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static Actor actor(UUID store, UUID employee, UUID terminal, Permission... permissions) {
        Set<String> authorities = Arrays.stream(permissions).map(p -> p.authority(store)).collect(Collectors.toSet());
        return Actor.employee(employee, terminal, store, authorities);
    }
}
