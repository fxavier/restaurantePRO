package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** D171: the poll thread of a {@link StrictStoreScope} test gets no fixture fallback (see AwaitilityFixtureScopeTest). */
@StrictStoreScope
class AwaitilityStrictScopeTest {

    @Test
    void aConditionOfAStrictTestIsUnset() {
        StoreScopes scopes = StoreScopeTestSupport.scopes();
        AtomicReference<StoreScope> seen = new AtomicReference<>();

        Awaitility.await().until(() -> {
            seen.set(scopes.current());
            return true;
        });

        assertThat(seen.get()).isEqualTo(StoreScope.UNSET);
    }
}
