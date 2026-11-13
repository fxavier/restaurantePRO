package com.xavier.backend.identity.oidc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code restaurantpro.security.oidc.*} (PRD R5 frozen names; Compose sets {@code RESTAURANTPRO_OIDC_*}, D144). An
 * unset variable reaches Spring as an empty string, so every blank value reads as unset and takes its default here:
 * a {@code @DefaultValue} does not apply to a present-but-empty value, and an empty string cannot bind to a primitive
 * {@code boolean}.
 *
 * @param enabled          {@code "true"} or {@code "false"} (any case), blank = {@code "false"}; anything else stops
 *                         startup. Kept as text so it has exactly one reading: the OIDC beans exist under
 *                         {@code @ConditionalOnProperty(havingValue = "true")}, which compares the raw text, while
 *                         Spring's boolean binding would also read {@code yes}, {@code on} or {@code 1} as true. Off by
 *                         default: no OIDC filter, routes or outbound call. Read it with {@link #isEnabled()}
 * @param issuerUri        the OpenID Provider; its discovery document is fetched on first use, never at startup
 * @param clientSecretFile a secret file with the client secret; unset or empty = a public client (PKCE only)
 * @param scopes           must contain {@code openid}; default {@code openid}
 * @param trustIdpMfa      P55: {@code false} = the IdP's own MFA never replaces the local second factor
 * @param postLoginUri     where the browser lands after a federated login or a link
 */
@ConfigurationProperties(OidcProperties.PREFIX)
public record OidcProperties(String enabled, String issuerUri, String clientId, String clientSecretFile,
                             String displayName, List<String> scopes, Boolean trustIdpMfa, String postLoginUri) {

    static final String PREFIX = "restaurantpro.security.oidc";

    public OidcProperties {
        enabled = enabled == null || enabled.isBlank() ? "false" : enabled;
        if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(PREFIX + ".enabled must be true or false, not '" + enabled + "'");
        }
        issuerUri = blankToNull(issuerUri);
        clientId = blankToNull(clientId);
        clientSecretFile = blankToNull(clientSecretFile);
        displayName = displayName == null || displayName.isBlank() ? "SSO" : displayName.strip();
        List<String> listed = scopes == null ? List.of()
                : scopes.stream().filter(scope -> scope != null && !scope.isBlank()).map(String::strip).toList();
        scopes = listed.isEmpty() ? List.of("openid") : listed;
        trustIdpMfa = Boolean.TRUE.equals(trustIdpMfa);
        postLoginUri = postLoginUri == null || postLoginUri.isBlank() ? "/backoffice/" : postLoginUri.strip();
    }

    /**
     * The startup check of an enabled provider: names every problem at once and never shows the secret.
     *
     * @throws IllegalStateException when enabled and incomplete
     */
    public boolean isEnabled() {
        return enabled.equalsIgnoreCase("true");
    }

    public void requireComplete() {
        if (!isEnabled()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        if (issuerUri == null) {
            problems.add(PREFIX + ".issuer-uri is required");
        }
        if (clientId == null) {
            problems.add(PREFIX + ".client-id is required");
        }
        if (!scopes.contains("openid")) {
            problems.add(PREFIX + ".scopes must contain openid");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("OIDC federation is misconfigured (" + PREFIX + ".enabled=true): "
                    + String.join("; ", problems));
        }
        if (issuerUri.endsWith("/")) {
            LoggerFactory.getLogger(OidcProperties.class).warn("{}.issuer-uri ends with '/': it must equal the "
                    + "provider's issuer exactly, so check the provider's discovery document", PREFIX);
        }
        clientSecret(); // an unreadable file stops startup now, not at the first login
    }

    /** @return the client secret, or {@code null} for a public client (no file, or an empty one) */
    public String clientSecret() {
        if (clientSecretFile == null) {
            return null;
        }
        try {
            return blankToNull(Files.readString(Path.of(clientSecretFile)));
        } catch (IOException e) {
            throw new IllegalStateException(PREFIX + ".client-secret-file cannot be read: " + clientSecretFile
                    + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
