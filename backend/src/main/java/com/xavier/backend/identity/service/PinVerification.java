package com.xavier.backend.identity.service;

import com.xavier.backend.audit.AuditEntry;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.model.Employee;
import com.xavier.backend.identity.repository.EmployeeRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks an employee code + PIN and counts failures (D26: the 5th consecutive wrong PIN locks the employee, and
 * the lock is audited). Shared by POS login and approver verification (D28).
 *
 * <p>Runs in its own transaction so the failure count commits even when the caller then fails (a login refusal,
 * or a refused approval that rolls back the caller's use case). The employee row is locked while checking, so
 * concurrent wrong PINs are all counted.
 */
@Service
class PinVerification {

    private final EmployeeRepository employees;
    private final PasswordEncoder encoder;
    private final AuditLog auditLog;
    private final Clock clock;
    /** Checked against when the code is unknown, so the answer takes as long as for a wrong PIN. */
    private final String unknownEmployeeHash;

    PinVerification(EmployeeRepository employees, PasswordEncoder encoder, AuditLog auditLog, Clock clock) {
        this.employees = employees;
        this.encoder = encoder;
        this.auditLog = auditLog;
        this.clock = clock;
        this.unknownEmployeeHash = encoder.encode("0000");
    }

    /**
     * @param storeId    where the attempt happened, for the lock's audit row
     * @param terminalId where the attempt happened, for the lock's audit row; {@code null} if not on a terminal
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    PinCheck verify(String employeeCode, String pin, UUID storeId, UUID terminalId) {
        Employee employee = employees.findByCodeForPinCheck(employeeCode.strip()).orElse(null);
        if (employee == null) {
            encoder.matches(pin, unknownEmployeeHash);
            return PinCheck.refused(null, "unknown employee code");
        }
        if (!employee.canLogIn()) {
            return PinCheck.refused(employee.getId(), employee.isLocked() ? "employee locked" : "employee inactive");
        }
        if (!encoder.matches(pin, employee.getPinHash())) {
            return recordFailure(employee, storeId, terminalId);
        }
        employee.recordSuccessfulPin(clock.instant());
        return PinCheck.accepted(employee.getId(), employee.getName());
    }

    private PinCheck recordFailure(Employee employee, UUID storeId, UUID terminalId) {
        if (!employee.recordFailedPin(clock.instant())) {
            return PinCheck.refused(employee.getId(), "wrong PIN");
        }
        auditLog.record(new AuditEntry(null, null, "EMPLOYEE_LOCKED", "Employee", employee.getId().toString(),
                storeId, terminalId, "Too many wrong PINs",
                Map.of("failedAttempts", employee.getFailedPinAttempts())));
        return PinCheck.refused(employee.getId(), "wrong PIN, employee now locked");
    }
}
