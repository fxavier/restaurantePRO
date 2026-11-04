package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(AccessPolicy.class)
@ExtendWith(OutputCaptureExtension.class)
class AccessPolicyTest {

    static final String STORE_A = "0190a000-0000-7000-8000-00000000000a";
    static final String STORE_B = "0190b000-0000-7000-8000-00000000000b";

    @Autowired
    AccessPolicy accessPolicy;

    @Test
    @WithActor(permissions = Permission.CATALOG_MANAGE)
    void deniesAPermissionTheActorDoesNotHave() {
        assertThatCode(() -> accessPolicy.require(Permission.CATALOG_MANAGE)).doesNotThrowAnyException();

        assertThatThrownBy(() -> accessPolicy.require(Permission.CATALOG_PUBLISH))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithActor(permissions = Permission.DINING_MANAGE, storeId = STORE_A)
    void aStorePermissionOnlyCountsInThatStore() {
        assertThatCode(() -> accessPolicy.require(Permission.DINING_MANAGE, uuid(STORE_A)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> accessPolicy.require(Permission.DINING_MANAGE, uuid(STORE_B)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accessPolicy.require(Permission.DINING_MANAGE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithActor(permissions = Permission.CATALOG_PUBLISH)
    void anInstallationWidePermissionCountsInEveryStore() {
        assertThatCode(() -> accessPolicy.require(Permission.CATALOG_PUBLISH, uuid(STORE_A)))
                .doesNotThrowAnyException();
        assertThatCode(() -> accessPolicy.require(Permission.CATALOG_PUBLISH, uuid(STORE_B)))
                .doesNotThrowAnyException();
    }

    @Test
    @WithActor(permissions = Permission.DINING_VIEW, storeId = STORE_A,
            employeeId = "0190e000-0000-7000-8000-00000000000e", terminalId = "0190f000-0000-7000-8000-00000000000f")
    void currentIsTheAuthenticatedActor() {
        Actor actor = accessPolicy.current();

        assertThat(actor.userId()).isEqualTo(uuid(WithActor.DEFAULT_USER_ID));
        assertThat(actor.employeeId()).isEqualTo(uuid("0190e000-0000-7000-8000-00000000000e"));
        assertThat(actor.terminalId()).isEqualTo(uuid("0190f000-0000-7000-8000-00000000000f"));
        assertThat(actor.storeId()).isEqualTo(uuid(STORE_A));
        assertThat(actor.authorities()).containsExactly("PERM_DINING_VIEW@" + STORE_A);
    }

    @Test
    void withoutAnAuthenticatedActorEverythingIsRefused() {
        assertThatThrownBy(() -> accessPolicy.current())
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> accessPolicy.require(Permission.CATALOG_VIEW))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> accessPolicy.require(Permission.CATALOG_VIEW, uuid(STORE_A)))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void theActorsAuthoritiesAreTheOnlySourceOfTruth() {
        Actor actor = new Actor(UUID.randomUUID(), null, null, null, Set.of(Permission.CATALOG_VIEW.authority()));
        // the Authentication claims something else; AccessPolicy must decide from the Actor alone
        var authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(Permission.CATALOG_PUBLISH.authority())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            assertThatCode(() -> accessPolicy.require(Permission.CATALOG_VIEW)).doesNotThrowAnyException();
            assertThatThrownBy(() -> accessPolicy.require(Permission.CATALOG_PUBLISH))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @WithActor(permissions = Permission.DINING_VIEW, storeId = STORE_A)
    void aDenialIsLoggedWithWhoWhatAndWhere(CapturedOutput output) {
        assertThatThrownBy(() -> accessPolicy.require(Permission.DINING_MANAGE, uuid(STORE_B)))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(output.getOut())
                .contains("Access denied")
                .contains(WithActor.DEFAULT_USER_ID)
                .contains("DINING_MANAGE")
                .contains(STORE_B);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
