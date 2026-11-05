package com.xavier.backend.reporting.service;

import com.xavier.backend.identity.EmployeeDirectory;
import com.xavier.backend.identity.EmployeeSummary;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Labels of the employees and terminals a report groups by, read when the report is served (D86). */
@Component
class Labels {

    private final EmployeeDirectory employees;
    private final OrganizationApi organization;

    Labels(EmployeeDirectory employees, OrganizationApi organization) {
        this.employees = employees;
        this.organization = organization;
    }

    /** Display names by employee id (as text); an unknown id has no label. */
    Map<String, String> employees(Collection<String> keys) {
        Set<UUID> ids = keys.stream().filter(Objects::nonNull).map(UUID::fromString).collect(Collectors.toSet());
        return employees.summaries(ids).values().stream()
                .collect(Collectors.toMap(e -> e.id().toString(), EmployeeSummary::displayName));
    }

    /** Terminal names by terminal id (as text); an unknown id has no label. */
    Map<String, String> terminals(Collection<String> keys) {
        return keys.stream().filter(Objects::nonNull).distinct()
                .flatMap(key -> organization.findTerminal(UUID.fromString(key)).stream())
                .collect(Collectors.toMap(t -> t.id().toString(), TerminalSummary::name));
    }
}
