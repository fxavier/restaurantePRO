package com.xavier.backend.identity.model.dto;

import java.util.UUID;

/** An employee as the BackOffice sees it. Never carries the PIN or its hash. */
public record EmployeeSummary(UUID id, String code, String name, boolean active, boolean locked, long version) {
}
