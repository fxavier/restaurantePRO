package com.xavier.backend.cash.service;

import com.xavier.backend.cash.model.CashClosing;
import com.xavier.backend.cash.model.CashClosingStatus;
import com.xavier.backend.cash.model.CashDayStatus;
import com.xavier.backend.cash.repository.CashClosingRepository;
import com.xavier.backend.cash.repository.CashDayReopeningRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The rounds of a cash day (D79 (3)): the current round is the number of financial reopens of the day, and a
 * register's closings are compared latest round first, then latest attempt. Read under the day's lock.
 */
@Component
class ClosingRounds {

    static final Comparator<CashClosing> LATEST = Comparator.comparingInt(CashClosing::getDayRound)
            .thenComparingInt(CashClosing::getAttemptNo);

    private final CashDayReopeningRepository reopenings;
    private final CashClosingRepository closings;

    ClosingRounds(CashDayReopeningRepository reopenings, CashClosingRepository closings) {
        this.reopenings = reopenings;
        this.closings = closings;
    }

    int current(UUID dayId) {
        return (int) reopenings.countByCashDayIdAndFromStatus(dayId, CashDayStatus.FINANCIALLY_CLOSED);
    }

    /** The register's latest closing of the day that was not REJECTED, if any. */
    Optional<CashClosing> latestLive(UUID registerId, UUID dayId) {
        return closings.findByRegisterIdAndCashDayIdAndStatusIn(registerId, dayId, CashClosingStatus.LIVE).stream()
                .max(LATEST);
    }

    boolean approvedInCurrentRound(UUID registerId, UUID dayId) {
        return closings.existsByRegisterIdAndCashDayIdAndDayRoundAndStatusIn(registerId, dayId, current(dayId),
                List.of(CashClosingStatus.APPROVED));
    }
}
