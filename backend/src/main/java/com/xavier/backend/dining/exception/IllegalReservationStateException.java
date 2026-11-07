package com.xavier.backend.dining.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** The reservation is no longer BOOKED (seated, cancelled or a no-show), so it cannot change (§8.3, D126). 409. */
public class IllegalReservationStateException extends ConflictException {

    private IllegalReservationStateException(String code, String detail) {
        super(code, detail);
    }

    public static IllegalReservationStateException notBooked(UUID reservationId) {
        return new IllegalReservationStateException("RESERVATION_NOT_BOOKED",
                "Reservation " + reservationId + " is not booked.");
    }
}
