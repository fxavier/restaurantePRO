package com.xavier.backend.dining.model.dto;

/** The reservation machine (spec §8.3, D126, P47): BOOKED → SEATED | CANCELLED | NO_SHOW. Only BOOKED moves. */
public enum ReservationStatus {
    BOOKED,
    SEATED,
    CANCELLED,
    NO_SHOW
}
