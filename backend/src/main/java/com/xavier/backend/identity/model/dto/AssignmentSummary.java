package com.xavier.backend.identity.model.dto;

import java.util.UUID;

public record AssignmentSummary(UUID id, UUID employeeId, UUID storeId, UUID roleId) {
}
