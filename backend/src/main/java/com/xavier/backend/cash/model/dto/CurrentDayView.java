package com.xavier.backend.cash.model.dto;

import com.xavier.backend.cash.DayView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The POS store's current cash day (P28), with the earlier days still OPEN that are left to close (they take
 * corrections only). {@code status} and {@code version} are as in {@link DayView}.
 */
public record CurrentDayView(UUID id, UUID storeId, LocalDate businessDate, String status, long version,
                             List<OpenDay> earlierOpenDays) {

    public static CurrentDayView of(DayView day, List<OpenDay> earlierOpenDays) {
        return new CurrentDayView(day.id(), day.storeId(), day.businessDate(), day.status(), day.version(),
                List.copyOf(earlierOpenDays));
    }

    /** An earlier OPEN cash day. */
    public record OpenDay(UUID id, LocalDate businessDate) {
    }
}
