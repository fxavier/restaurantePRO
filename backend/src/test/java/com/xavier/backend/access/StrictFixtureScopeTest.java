package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * D171: in a {@link StrictStoreScope} class the test thread is unset, and fixture setup that must see every store
 * borrows the test-only fixture scope for the call only, never a production {@link SystemScope}.
 */
@StrictStoreScope
class StrictFixtureScopeTest {

    @Test
    void aStrictTestBorrowsTheFixtureScopeForItsSetupOnly() {
        StoreScopes scopes = StoreScopeTestSupport.scopes();
        UUID account = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                Actor.account(account, Set.of()), null, List.of()));
        try {
            assertThat(StoreScopeTestSupport.inStrictFixture(() -> scopes.current())).satisfies(scope -> {
                assertThat(scope.origin()).isEqualTo(StoreScopeTestSupport.ORIGIN);
                assertThat(scope.allStoresSetting()).isEqualTo("on");
            });
            assertThat(scopes.current().origin()).as("the actor is back").isEqualTo("account:" + account);
            SecurityContextHolder.clearContext();
            assertThat(scopes.current()).as("and the strict thread is unset again").isEqualTo(StoreScope.UNSET);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
