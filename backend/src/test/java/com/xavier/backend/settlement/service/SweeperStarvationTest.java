package com.xavier.backend.settlement.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.repository.CreditChargeRepository;
import com.xavier.backend.settlement.repository.Keyed;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * D185: a batch cap with oldest-first order must not starve a newer item when {@code cap} older items stay eligible
 * run after run (a CREATED attempt whose submit is refused again and again; a RESERVED charge whose document is still
 * a DRAFT). The repositories are faked in memory with the real queries' keyset order.
 */
class SweeperStarvationTest {

    static final int CAP = 3;
    static final Instant T0 = Instant.parse("2026-11-15T09:00:00Z");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);

    /** CAP + 1 permanently eligible items, then one newer item that a run must reach. */
    final List<Keyed> queue = new ArrayList<>();
    final UUID eligible = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

    SweeperStarvationTest() {
        for (int i = 0; i <= CAP; i++) {
            queue.add(new Keyed(UUID.fromString("00000000-0000-7000-8000-00000000000" + i), T0.plusSeconds(i)));
        }
        queue.add(new Keyed(eligible, T0.plusSeconds(60)));
    }

    @Test
    void anEligibleAttemptBehindCapStuckOnesIsSubmittedWithinTwoRuns() {
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        when(attempts.findStale(any(), any(), any(), any(), any(), any())).thenAnswer(call -> call.getArgument(0)
                == AttemptStatus.CREATED ? after(call.getArgument(3), call.getArgument(4), call.getArgument(5))
                : List.of());
        PaymentSubmitter submitter = mock(PaymentSubmitter.class);
        UUID tenant = UUID.randomUUID();
        AttemptSweeper sweeper = SweeperBatchTest.attemptSweeper(attempts, submitter, CAP, tenant);

        sweeper.sweep();
        sweeper.sweep();

        verify(submitter).submit(eligible);
    }

    @Test
    void anEligibleChargeBehindCapStuckOnesIsTriedWithinTwoRuns() {
        CreditChargeRepository charges = mock(CreditChargeRepository.class);
        when(charges.findReservedWithDocument(any(), any(), any(), any())).thenAnswer(call -> after(
                call.getArgument(1), call.getArgument(2), call.getArgument(3)));
        CreditChargeSweeper sweeper = new CreditChargeSweeper(charges, null, null,
                mock(PlatformTransactionManager.class), SweeperBatchTest.PROPERTIES, CLOCK, CAP,
                SweeperBatchTest.tenantOf(UUID.randomUUID()));

        sweeper.sweep();
        sweeper.sweep();

        verify(charges).findRefById(eligible);
    }

    /** The queue after ({@code at}, {@code id}) in (at, id) order, at most the page size: the real keyset query. */
    private List<Keyed> after(Instant at, UUID id, Pageable limit) {
        return queue.stream().filter(k -> k.at().isAfter(at) || (k.at().equals(at) && k.id().compareTo(id) > 0))
                .limit(limit.getPageSize()).toList();
    }
}
