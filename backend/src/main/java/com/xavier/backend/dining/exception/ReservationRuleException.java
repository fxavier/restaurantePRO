package com.xavier.backend.dining.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * A reservation request breaks a business rule (D126, P47): a no-show before the start, a reservation or table of
 * another store, an unknown customer, or a calendar range that is reversed or too long. 422.
 */
public class ReservationRuleException extends BusinessRuleException {

    private ReservationRuleException(String code, String detail, Map<String, ?> properties) {
        super(code, detail, properties);
    }

    public static ReservationRuleException notStarted(UUID reservationId) {
        return new ReservationRuleException("RESERVATION_NOT_STARTED",
                "Reservation " + reservationId + " has not started: it cannot be a no-show yet.", Map.of());
    }

    public static ReservationRuleException otherStore(UUID reservationId) {
        return new ReservationRuleException("RESERVATION_OTHER_STORE",
                "Reservation " + reservationId + " is not in this store.", Map.of());
    }

    public static ReservationRuleException tableOtherStore(UUID tableId) {
        return new ReservationRuleException("TABLE_OTHER_STORE", "Table " + tableId + " is not in this store.",
                Map.of());
    }

    public static ReservationRuleException customerNotFound(UUID customerId) {
        return new ReservationRuleException("CUSTOMER_NOT_FOUND", "Customer " + customerId + " was not found.",
                Map.of());
    }

    public static ReservationRuleException reversed(LocalDate from, LocalDate to) {
        return new ReservationRuleException("RANGE_REVERSED",
                "The range starts on " + from + ", after its end " + to + ".", Map.of());
    }

    public static ReservationRuleException tooLarge(long maxDays) {
        return new ReservationRuleException("RANGE_TOO_LARGE",
                "The range is longer than " + maxDays + " days: narrow it.", Map.of("maxDays", maxDays));
    }
}
