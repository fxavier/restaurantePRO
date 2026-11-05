package com.xavier.backend.identity;

import com.xavier.backend.identity.repository.EmployeeRepository;
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

    EmployeeDirectory(EmployeeRepository employees) {
        this.employees = employees;
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
