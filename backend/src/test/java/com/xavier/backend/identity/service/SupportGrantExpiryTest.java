package com.xavier.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One grant that cannot be expired must not keep the others (and their sessions) alive. */
class SupportGrantExpiryTest {

    @Test
    void aFailingGrantDoesNotStopTheRun() {
        SupportGrantService grants = mock(SupportGrantService.class);
        UUID broken = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(grants.dueGrantIds()).thenReturn(List.of(broken, second, third));
        when(grants.expire(broken)).thenThrow(new IllegalStateException("database hiccup"));
        when(grants.expire(second)).thenReturn(true);
        when(grants.expire(third)).thenReturn(true);

        int expired = new SupportGrantExpiry(grants).expireDue();

        assertThat(expired).isEqualTo(2);
        verify(grants).expire(third);
    }
}
