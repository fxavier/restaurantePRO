package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.exception.RoleNotFoundException;
import com.xavier.backend.identity.model.EmployeeStoreAssignment;
import com.xavier.backend.identity.repository.EmployeeStoreAssignmentRepository;
import com.xavier.backend.identity.repository.RoleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may change what an employee can do (spec §10, least privilege): {@code EMPLOYEE_MANAGE} and every permission
 * of the employee's role, in every store the employee works in (the {@code requireCanAssign} rule, per assignment).
 * Otherwise a manager of one store could take over a manager of another store: reset their PIN, add them to their
 * own store, or link an account to them and log in with their store authorities (r2-decisions §12). An employee
 * without assignments needs an installation-wide grant.
 */
@Component
class EmployeeControl {

    private final EmployeeStoreAssignmentRepository assignments;
    private final RoleRepository roles;
    private final AccessPolicy accessPolicy;

    EmployeeControl(EmployeeStoreAssignmentRepository assignments, RoleRepository roles, AccessPolicy accessPolicy) {
        this.assignments = assignments;
        this.roles = roles;
        this.accessPolicy = accessPolicy;
    }

    void require(UUID employeeId) {
        List<EmployeeStoreAssignment> held = assignments.findByEmployeeId(employeeId);
        if (held.isEmpty()) {
            accessPolicy.require(Permission.EMPLOYEE_MANAGE);
        }
        for (EmployeeStoreAssignment assignment : held) {
            accessPolicy.require(Permission.EMPLOYEE_MANAGE, assignment.getStoreId());
            roles.findById(assignment.getRoleId()).orElseThrow(() -> new RoleNotFoundException(assignment.getRoleId()))
                    .getPermissions().forEach(permission -> accessPolicy.require(permission, assignment.getStoreId()));
        }
    }
}
