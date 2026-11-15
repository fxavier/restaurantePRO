package com.xavier.backend.access;

import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

/**
 * The authentication the request pipeline builds once a caller is resolved ({@link ActorAuthentication}), for tests
 * that act as an {@link Actor} without a token: a BackOffice account that proved its second factor at {@code
 * authTime} ({@code acr 2}, D184), or a POS employee (no second factor, as a PIN session).
 */
public final class TestAuthentications {

    private TestAuthentications() {
    }

    /** {@code actor} in {@code tenantId}, authenticated now (with a second factor when it is an account). */
    public static Authentication of(Actor actor, UUID tenantId) {
        return of(actor, tenantId, Instant.now());
    }

    /** {@code actor} in the bootstrap tenant, authenticated now. */
    public static Authentication of(Actor actor) {
        return of(actor, TenantTestSupport.BOOTSTRAP);
    }

    /** {@code actor} in {@code tenantId}; an account proved its second factor at {@code authTime}. */
    public static Authentication of(Actor actor, UUID tenantId, Instant authTime) {
        boolean account = actor.kind() == ActorKind.ACCOUNT;
        return new ActorAuthentication(actor, new TenantContext(tenantId, account ? MembershipKind.ACCOUNT
                : MembershipKind.TERMINAL, account ? actor.userId() : principalOf(actor), "test:actor"),
                account ? new Assurance(Assurance.SECOND_FACTOR, authTime) : Assurance.NONE);
    }

    /** As a MockMvc request's authentication. */
    public static RequestPostProcessor as(Actor actor) {
        return SecurityMockMvcRequestPostProcessors.authentication(of(actor));
    }

    /** As a MockMvc request's authentication, in {@code tenantId}. */
    public static RequestPostProcessor as(Actor actor, UUID tenantId) {
        return SecurityMockMvcRequestPostProcessors.authentication(of(actor, tenantId));
    }

    private static UUID principalOf(Actor actor) {
        return actor.terminalId() != null ? actor.terminalId() : actor.userId();
    }
}
