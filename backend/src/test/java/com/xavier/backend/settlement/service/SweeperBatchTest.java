package com.xavier.backend.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.repository.CreditChargeRepository;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.shared.tenancy.TenantScopes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * D185 (spec: a job handles a bounded batch per tenant per tick, {@code restaurantpro.jobs.batch-per-tenant}): the
 * two settlement sweepers ask the repository for at most that many items, so one tenant's backlog never holds the
 * shared scheduler. The repository (the module's own boundary to the database) is the only fake.
 */
class SweeperBatchTest {

    static final SettlementProperties PROPERTIES = new SettlementProperties(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(30), 4, Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofMinutes(15),
                Duration.ofHours(24), 20);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);

    static AttemptSweeper attemptSweeper(PaymentAttemptRepository attempts, PaymentSubmitter submitter, int batch,
                                         UUID tenant) {
        TenantScopes tenants = mock(TenantScopes.class);
        when(tenants.requireTenant()).thenReturn(tenant);
        return new AttemptSweeper(attempts, submitter, null, PROPERTIES, mock(PlatformTransactionManager.class), CLOCK,
                batch, tenants);
    }

    static TenantScopes tenantOf(UUID tenant) {
        TenantScopes tenants = mock(TenantScopes.class);
        when(tenants.requireTenant()).thenReturn(tenant);
        return tenants;
    }

    @Test
    void theAttemptSweeperAsksForAtMostTheTenantBatchOfEachKind() {
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        when(attempts.findStale(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        AttemptSweeper sweeper = attemptSweeper(attempts, null, 7, UUID.randomUUID());

        sweeper.sweep();

        ArgumentCaptor<Pageable> limit = ArgumentCaptor.forClass(Pageable.class);
        verify(attempts).findStale(eq(AttemptStatus.CREATED), eq(PaymentMethodKind.INTEGRATED), any(), any(),
                any(), limit.capture());
        verify(attempts).findStale(eq(AttemptStatus.PENDING), eq(PaymentMethodKind.INTEGRATED), any(), any(), any(),
                limit.capture());
        assertThat(limit.getAllValues()).extracting(Pageable::getPageSize).containsExactly(7, 7);
    }

    @Test
    void theCreditSweeperAsksForAtMostTheTenantBatch() {
        CreditChargeRepository charges = mock(CreditChargeRepository.class);
        when(charges.findReservedWithDocument(any(), any(), any(), any())).thenReturn(List.of());
        CreditChargeSweeper sweeper = new CreditChargeSweeper(charges, null, null,
                mock(PlatformTransactionManager.class), PROPERTIES, CLOCK, 5, tenantOf(UUID.randomUUID()));

        sweeper.sweep();

        ArgumentCaptor<Pageable> limit = ArgumentCaptor.forClass(Pageable.class);
        verify(charges).findReservedWithDocument(any(Instant.class), any(), any(), limit.capture());
        assertThat(limit.getValue().getPageSize()).isEqualTo(5);
    }
}
