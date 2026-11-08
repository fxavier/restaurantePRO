package com.xavier.backend.identity;

import com.xavier.backend.identity.model.Employee;
import com.xavier.backend.identity.repository.EmployeeRepository;
import com.xavier.backend.identity.repository.EmployeeStoreAssignmentRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee labels for other modules (D66), e.g. the operator column of an export. Read-only and without
 * authorization: the caller authorized its own use case. Locked or inactive employees are included (old facts name
 * them too).
 */
@Service
public class EmployeeDirectory {

    private final EmployeeRepository employees;
    private final EmployeeStoreAssignmentRepository assignments;

    EmployeeDirectory(EmployeeRepository employees, EmployeeStoreAssignmentRepository assignments) {
        this.employees = employees;
        this.assignments = assignments;
    }

    /**
     * Whether {@code employeeId} is an active employee with at least one assignment in {@code storeId} (S50: a
     * delivery's courier). A locked employee (PIN lockout) still counts: the lock concerns login, not employment.
     * {@code false} for null arguments or an unknown employee; never throws.
     */
    @Transactional(readOnly = true)
    public boolean isActiveInStore(UUID employeeId, UUID storeId) {
        if (employeeId == null || storeId == null) {
            return false; // findById(null) would throw and mark a joined caller transaction rollback-only
        }
        return employees.findById(employeeId).filter(Employee::isActive).isPresent()
                && !assignments.findByEmployeeIdAndStoreId(employeeId, storeId).isEmpty();
    }

    /** The summary of every known id in {@code ids}; unknown ids are absent from the map. */
    @Transactional(readOnly = true)
    public Map<UUID, EmployeeSummary> summaries(Collection<UUID> ids) {
        Set<UUID> distinct = Set.copyOf(ids);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return employees.findAllById(distinct).stream()
                .map(employee -> new EmployeeSummary(employee.getId(), employee.getCode(), employee.getName()))
                .collect(Collectors.toUnmodifiableMap(EmployeeSummary::id, Function.identity()));
    }
}
