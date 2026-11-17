package com.xavier.backend.organization.model.dto;

import java.util.UUID;

/**
 * An enrolled device: its id and store, and the Keycloak client it now obtains tokens for (client credentials with a
 * signed assertion, RFC 7523). No secret: there is none.
 */
public record DeviceEnrolmentResponse(UUID id, UUID storeId, String clientId) {
}
