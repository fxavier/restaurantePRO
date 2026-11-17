package com.xavier.backend.access;

import com.xavier.backend.shared.tenancy.TenantContext;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authentication of a request once its caller is resolved (D177 step 7): the {@link Actor} built from the
 * tenant's own identity rows, the {@link TenantContext} of its membership (the details, read by
 * {@code TenantScopes}) and the {@link Assurance} of its token (read by {@link AccessPolicy} for step-up). Its
 * granted authorities are exactly {@link Actor#authorities()} (URL rules such as the actuator's read them); no claim
 * of the token ever grants one. Never stored: every request resolves again.
 */
public final class ActorAuthentication extends AbstractAuthenticationToken {

    private final Actor actor;
    private final Assurance assurance;

    public ActorAuthentication(Actor actor, TenantContext tenant, Assurance assurance) {
        super(Objects.requireNonNull(actor, "actor").authorities().stream().map(SimpleGrantedAuthority::new).toList());
        this.actor = actor;
        this.assurance = Objects.requireNonNull(assurance, "assurance");
        setDetails(Objects.requireNonNull(tenant, "tenant"));
        setAuthenticated(true);
    }

    @Override
    public Actor getPrincipal() {
        return actor;
    }

    /** No credential is kept: the token was checked and is not needed again. */
    @Override
    public Object getCredentials() {
        return null;
    }

    public Assurance assurance() {
        return assurance;
    }

    public TenantContext tenant() {
        return (TenantContext) getDetails();
    }
}
