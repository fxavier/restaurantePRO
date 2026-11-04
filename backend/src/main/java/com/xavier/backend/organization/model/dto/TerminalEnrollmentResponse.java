package com.xavier.backend.organization.model.dto;

import java.util.UUID;

/** Returned once, to the enrolling device. The device sends {@code deviceToken} on every POS login. */
public record TerminalEnrollmentResponse(UUID terminalId, UUID storeId, String deviceToken) {
}
