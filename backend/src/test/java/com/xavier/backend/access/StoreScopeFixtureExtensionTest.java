package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * D171: the extension (auto-detected through {@code junit-platform.properties}) marks the JUnit thread, so the fixture
 * fallback gives fixture code all stores there and nowhere else; {@link StrictStoreScope} turns it off.
 */
class StoreScopeFixtureExtensionTest {

    @Test
    void theJunitThreadOfATestIsAFixtureThread() throws Exception {
        assertThat(StoreScopeTestSupport.FIXTURE_FALLBACK.whenNoActor().origin()).isEqualTo("test-fixture");
        assertThat(StoreScopeTestSupport.FIXTURE_FALLBACK.whenNoActor().allStoresSetting()).isEqualTo("on");
        assertThat(CompletableFuture.supplyAsync(StoreScopeTestSupport.FIXTURE_FALLBACK::whenNoActor).get())
                .as("any other thread has production semantics").isEqualTo(StoreScope.UNSET);
    }

    @Test
    void asFixtureClearsTheActorForTheCallOnly() {
        StoreScopes scopes = StoreScopeTestSupport.scopes();
        UUID account = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                Actor.account(account, Set.of()), null, java.util.List.of()));
        try {
            assertThat(StoreScopeTestSupport.asFixture(() -> scopes.current().origin())).isEqualTo("test-fixture");
            assertThat(scopes.current().origin()).isEqualTo("account:" + account);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
