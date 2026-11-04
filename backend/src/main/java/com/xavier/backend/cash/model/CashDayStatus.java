package com.xavier.backend.cash.model;

/** spec §8.3. A reopen is an event ({@link CashDayReopening}), never a state. */
public enum CashDayStatus {
    OPEN, OPERATIONALLY_CLOSED, FINANCIALLY_CLOSED
}
