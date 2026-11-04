package com.xavier.backend.identity.service;

import java.util.UUID;

/**
 * The result of checking an employee code + PIN.
 *
 * @param refusal why it was refused, for the WARN log (never the PIN); {@code null} when accepted
 */
record PinCheck(UUID employeeId, String name, String refusal) {

    static PinCheck accepted(UUID employeeId, String name) {
        return new PinCheck(employeeId, name, null);
    }

    static PinCheck refused(UUID employeeId, String refusal) {
        return new PinCheck(employeeId, null, refusal);
    }

    boolean isAccepted() {
        return refusal == null;
    }
}
