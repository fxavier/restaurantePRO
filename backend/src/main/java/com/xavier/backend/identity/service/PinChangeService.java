package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.InvalidCredentialsException;
import com.xavier.backend.identity.exception.PinChangeNotRequiredException;
import com.xavier.backend.identity.exception.PinUnchangedException;
import com.xavier.backend.identity.model.Employee;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.EmployeeRepository;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * After a manager's PIN reset the employee's POS session holds nothing until they choose their own PIN
 * (r2-decisions §12). Needs only that session: it was opened with the reset PIN moments ago.
 */
@Service
public class PinChangeService {

    private static final Logger log = LoggerFactory.getLogger(PinChangeService.class);

    private final EmployeeRepository employees;
    private final AuthorityResolver authorities;
    private final PasswordEncoder encoder;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    PinChangeService(EmployeeRepository employees, AuthorityResolver authorities, PasswordEncoder encoder,
                     AccessPolicy accessPolicy, AuditLog auditLog, Clock clock) {
        this.employees = employees;
        this.authorities = authorities;
        this.encoder = encoder;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** @return the session the employee now gets on this terminal, with their authorities in its store */
    @Transactional
    public SessionView choosePin(String newPin) {
        Actor actor = accessPolicy.current();
        if (actor.kind() != ActorKind.EMPLOYEE || actor.terminalId() == null) {
            throw new PinChangeNotRequiredException();
        }
        Employee employee = employees.findById(actor.employeeId()).orElseThrow(InvalidCredentialsException::new);
        if (!employee.isMustChangePin()) {
            throw new PinChangeNotRequiredException();
        }
        if (!employee.canLogIn()) {
            log.warn("PIN change refused for employee {}: {}", employee.getId(),
                    employee.isLocked() ? "employee locked" : "employee inactive");
            throw new InvalidCredentialsException();
        }
        if (encoder.matches(newPin, employee.getPinHash())) {
            throw new PinUnchangedException();
        }
        employee.choosePin(encoder.encode(newPin), clock.instant());
        employees.saveAndFlush(employee);
        auditLog.recordAsCurrentActor("EMPLOYEE_PIN_CHANGED", "Employee", employee.getId().toString(),
                actor.storeId(), null, null, Map.of());
        Actor withAuthorities = Actor.employee(employee.getId(), actor.terminalId(), actor.storeId(),
                authorities.ofEmployeeInStore(employee.getId(), actor.storeId()));
        return SessionView.employee(withAuthorities, employee.getName(), false);
    }
}
