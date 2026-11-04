package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditEntry;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.AssignmentNotFoundException;
import com.xavier.backend.identity.exception.DuplicateAssignmentException;
import com.xavier.backend.identity.exception.EmployeeCodeTakenException;
import com.xavier.backend.identity.exception.EmployeeNotFoundException;
import com.xavier.backend.identity.exception.RoleNotFoundException;
import com.xavier.backend.identity.exception.StoreNotFoundException;
import com.xavier.backend.identity.model.Employee;
import com.xavier.backend.identity.model.EmployeeStoreAssignment;
import com.xavier.backend.identity.model.Role;
import com.xavier.backend.identity.model.dto.AssignmentRequest;
import com.xavier.backend.identity.model.dto.AssignmentSummary;
import com.xavier.backend.identity.model.dto.EmployeeCreateRequest;
import com.xavier.backend.identity.model.dto.EmployeeSummary;
import com.xavier.backend.identity.model.dto.EmployeeUpdateRequest;
import com.xavier.backend.identity.repository.EmployeeRepository;
import com.xavier.backend.identity.repository.EmployeeStoreAssignmentRepository;
import com.xavier.backend.identity.repository.RoleRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employees, their PINs and store assignments (spec §5 "Acessos", D26, D27). {@code EMPLOYEE_MANAGE} is
 * store-scoped: hiring and assigning need it in the target store; acting on an existing employee needs it in
 * one of the employee's stores (or installation-wide). Nobody can hand out a permission they do not hold in that
 * store (spec §10, least privilege).
 */
@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final EmployeeStoreAssignmentRepository assignments;
    private final RoleRepository roles;
    private final OrganizationApi organization;
    private final PasswordEncoder encoder;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final SessionTermination sessions;
    private final Clock clock;

    EmployeeService(EmployeeRepository employees, EmployeeStoreAssignmentRepository assignments,
                    RoleRepository roles, OrganizationApi organization, PasswordEncoder encoder,
                    AccessPolicy accessPolicy, AuditLog auditLog, SessionTermination sessions, Clock clock) {
        this.employees = employees;
        this.assignments = assignments;
        this.roles = roles;
        this.organization = organization;
        this.encoder = encoder;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public EmployeeSummary hire(EmployeeCreateRequest request) {
        Role role = requireCanAssign(request.storeId(), request.roleId());
        if (employees.existsByCode(request.code().strip())) {
            throw new EmployeeCodeTakenException(request.code());
        }
        Employee employee = save(Employee.hire(request.code(), request.name(), encoder.encode(request.pin()),
                clock.instant()));
        audit("EMPLOYEE_HIRED", employee, request.storeId(), Map.of("code", employee.getCode()));
        assign(employee, request.storeId(), role);
        return employee.toSummary();
    }

    @Transactional
    public EmployeeSummary update(UUID id, EmployeeUpdateRequest request) {
        Employee employee = controllable(id);
        OptimisticLocking.requireVersion(Employee.class, id, employee.getVersion(), request.version());
        employee.update(request.name(), request.active(), clock.instant());
        employees.saveAndFlush(employee);
        audit("EMPLOYEE_UPDATED", employee, null, Map.of("name", employee.getName(), "active", request.active()));
        if (!request.active()) {
            sessions.endEmployeeSessions(employee.getId());
        }
        return employee.toSummary();
    }

    @Transactional(readOnly = true)
    public EmployeeSummary get(UUID id) {
        return manageable(id).toSummary();
    }

    /** With a store: the employees assigned to it. Without: everyone, which needs an installation-wide grant. */
    @Transactional(readOnly = true)
    public PageResponse<EmployeeSummary> list(UUID storeId, Pageable pageable) {
        if (storeId == null) {
            accessPolicy.require(Permission.EMPLOYEE_MANAGE);
            return PageResponse.from(employees.findAll(pageable).map(Employee::toSummary));
        }
        accessPolicy.require(Permission.EMPLOYEE_MANAGE, storeId);
        return PageResponse.from(employees.findAssignedTo(storeId, pageable).map(Employee::toSummary));
    }

    @Transactional
    public EmployeeSummary unlock(UUID id) {
        Employee employee = controllable(id);
        employee.unlock(clock.instant());
        employees.saveAndFlush(employee);
        audit("EMPLOYEE_UNLOCKED", employee, null, Map.of());
        return employee.toSummary();
    }

    /** A new PIN; the failed attempts were against the old one, so it also unlocks. */
    @Transactional
    public EmployeeSummary resetPin(UUID id, String pin) {
        Employee employee = controllable(id);
        employee.resetPin(encoder.encode(pin), clock.instant());
        employees.saveAndFlush(employee);
        audit("EMPLOYEE_PIN_RESET", employee, null, Map.of());
        return employee.toSummary();
    }

    @Transactional
    public AssignmentSummary addAssignment(UUID employeeId, AssignmentRequest request) {
        Role role = requireCanAssign(request.storeId(), request.roleId());
        Employee employee = controllable(employeeId);
        if (assignments.existsByEmployeeIdAndStoreIdAndRoleId(employeeId, request.storeId(), role.getId())) {
            throw new DuplicateAssignmentException();
        }
        return assign(employee, request.storeId(), role).toSummary();
    }

    @Transactional(readOnly = true)
    public List<AssignmentSummary> assignments(UUID employeeId) {
        manageable(employeeId);
        return assignments.findByEmployeeId(employeeId).stream().map(EmployeeStoreAssignment::toSummary).toList();
    }

    @Transactional
    public void removeAssignment(UUID employeeId, UUID assignmentId) {
        EmployeeStoreAssignment assignment = assignments.findById(assignmentId)
                .filter(found -> found.getEmployeeId().equals(employeeId))
                .orElseThrow(() -> new AssignmentNotFoundException(assignmentId));
        accessPolicy.require(Permission.EMPLOYEE_MANAGE, assignment.getStoreId());
        assignments.delete(assignment);
        sessions.endEmployeeSessions(employeeId);
        auditLog.record(new AuditEntry(accessPolicy.current().userId(), null, "EMPLOYEE_UNASSIGNED", "Employee",
                employeeId.toString(), assignment.getStoreId(), null, null,
                Map.of("roleId", assignment.getRoleId().toString())));
    }

    /**
     * {@code EMPLOYEE_MANAGE} in the store, and every permission of the role in that store too: a store manager
     * cannot make someone more powerful than themselves.
     */
    private Role requireCanAssign(UUID storeId, UUID roleId) {
        accessPolicy.require(Permission.EMPLOYEE_MANAGE, storeId);
        if (!organization.storeExists(storeId)) {
            throw new StoreNotFoundException(storeId);
        }
        Role role = roles.findById(roleId).orElseThrow(() -> new RoleNotFoundException(roleId));
        role.getPermissions().forEach(permission -> accessPolicy.require(permission, storeId));
        return role;
    }

    private EmployeeStoreAssignment assign(Employee employee, UUID storeId, Role role) {
        EmployeeStoreAssignment assignment;
        try {
            assignment = assignments.saveAndFlush(
                    new EmployeeStoreAssignment(employee.getId(), storeId, role.getId(), clock.instant()));
        } catch (DataIntegrityViolationException e) {
            if (UniqueConstraints.isViolated(e, "employee_store_assignment_uk")) {
                throw new DuplicateAssignmentException();
            }
            throw e;
        }
        audit("EMPLOYEE_ASSIGNED", employee, storeId, Map.of("roleId", role.getId().toString(),
                "role", role.getName()));
        return assignment;
    }

    /**
     * The employee, if the actor may change what they can do (PIN, lock, active, new stores): {@code EMPLOYEE_MANAGE}
     * and every permission of the employee's role, in every store the employee works in. Otherwise a manager of one
     * store could add a manager of another store to theirs, reset that PIN and log in as them over there.
     * An employee without assignments needs an installation-wide grant.
     */
    private Employee controllable(UUID id) {
        Employee employee = employees.findById(id).orElseThrow(() -> new EmployeeNotFoundException(id));
        List<EmployeeStoreAssignment> held = assignments.findByEmployeeId(id);
        if (held.isEmpty()) {
            accessPolicy.require(Permission.EMPLOYEE_MANAGE);
        }
        for (EmployeeStoreAssignment assignment : held) {
            accessPolicy.require(Permission.EMPLOYEE_MANAGE, assignment.getStoreId());
            roles.findById(assignment.getRoleId()).orElseThrow(() -> new RoleNotFoundException(assignment.getRoleId()))
                    .getPermissions().forEach(permission -> accessPolicy.require(permission, assignment.getStoreId()));
        }
        return employee;
    }

    /** The employee, if the actor may read them: manages one of their stores, or installation-wide. */
    private Employee manageable(UUID id) {
        Employee employee = employees.findById(id).orElseThrow(() -> new EmployeeNotFoundException(id));
        Actor actor = accessPolicy.current();
        assignments.findByEmployeeId(id).stream()
                .map(EmployeeStoreAssignment::getStoreId)
                .filter(store -> actor.authorities().contains(Permission.EMPLOYEE_MANAGE.authority(store)))
                .findFirst()
                .ifPresentOrElse(store -> accessPolicy.require(Permission.EMPLOYEE_MANAGE, store),
                        () -> accessPolicy.require(Permission.EMPLOYEE_MANAGE));
        return employee;
    }

    private Employee save(Employee employee) {
        return UniqueConstraints.saveAndFlush(employees, employee,
                () -> new EmployeeCodeTakenException(employee.getCode()), "employee_code_uk");
    }

    private void audit(String action, Employee employee, UUID storeId, Map<String, Object> details) {
        auditLog.record(new AuditEntry(accessPolicy.current().userId(), null, action, "Employee",
                employee.getId().toString(), storeId, null, null, details));
    }
}
