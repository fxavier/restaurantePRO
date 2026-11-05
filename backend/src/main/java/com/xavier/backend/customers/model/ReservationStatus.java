package com.xavier.backend.customers.model;

/** {@code ACTIVE → CONSUMED} (its debit is posted) or {@code ACTIVE → RELEASED} (the charge was cancelled). */
public enum ReservationStatus { ACTIVE, CONSUMED, RELEASED }
