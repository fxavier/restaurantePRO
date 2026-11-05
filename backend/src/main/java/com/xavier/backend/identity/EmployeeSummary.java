package com.xavier.backend.identity;

import java.util.UUID;

/**
 * An employee's label for other modules ({@link EmployeeDirectory}): code and display name only, no PIN, state or
 * store assignments. Not the BackOffice {@code model.dto.EmployeeSummary}, which is internal to identity.
 */
public record EmployeeSummary(UUID id, String code, String displayName) {
}
