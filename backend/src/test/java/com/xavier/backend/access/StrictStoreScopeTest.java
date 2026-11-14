package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** D171: {@link StrictStoreScope} turns the fixture fallback off on the JUnit thread too. */
@StrictStoreScope
class StrictStoreScopeTest {

    @Test
    void aStrictTestHasNoFixtureFallback() {
        assertThat(StoreScopeTestSupport.FIXTURE_FALLBACK.whenNoActor()).isEqualTo(StoreScope.UNSET);
    }
}
