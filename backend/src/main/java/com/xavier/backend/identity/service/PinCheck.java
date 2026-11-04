package com.xavier.backend.identity.service;

import java.util.UUID;

/**
 * The result of checking an employee code + PIN.
 *
 * @param mustChangePin the PIN is one a manager set (reset-pin): good for choosing a new PIN, nothing else
 * @param refusal       why it was refused, for the WARN log (never the PIN); {@code null} when accepted
 */
record PinCheck(UUID employeeId, String name, boolean mustChangePin, String refusal) {

    static PinCheck accepted(UUID employeeId, String name, boolean mustChangePin) {
        return new PinCheck(employeeId, name, mustChangePin, null);
    }

    static PinCheck refused(UUID employeeId, String refusal) {
        return new PinCheck(employeeId, null, false, refusal);
    }

    boolean isAccepted() {
        return refusal == null;
    }
}
