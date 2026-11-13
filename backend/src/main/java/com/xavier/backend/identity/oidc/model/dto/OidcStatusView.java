package com.xavier.backend.identity.oidc.model.dto;

/** Whether the login page shows the federated-login button, and its label (D144). */
public record OidcStatusView(boolean enabled, String displayName) {
}
