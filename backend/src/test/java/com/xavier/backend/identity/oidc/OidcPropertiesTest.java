package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@code restaurantpro.security.oidc.*} as Compose passes it: an unset {@code RESTAURANTPRO_OIDC_*} variable arrives
 * as an empty string (application.yaml {@code ${VAR:}}), which must read as unset, never as a value.
 */
class OidcPropertiesTest {

    private static final String PREFIX = "restaurantpro.security.oidc";

    @TempDir
    Path dir;

    @Test
    void blankValuesReadAsUnsetAndTakeTheDefaults() {
        OidcProperties properties = bind(Map.of("enabled", "", "issuer-uri", "", "client-id", " ",
                "client-secret-file", "", "display-name", "", "scopes", "", "trust-idp-mfa", "",
                "post-login-uri", ""));

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.issuerUri()).isNull();
        assertThat(properties.clientId()).isNull();
        assertThat(properties.clientSecretFile()).isNull();
        assertThat(properties.displayName()).isEqualTo("SSO");
        assertThat(properties.scopes()).containsExactly("openid");
        assertThat(properties.trustIdpMfa()).isFalse();
        assertThat(properties.postLoginUri()).isEqualTo("/backoffice/");
    }

    @Test
    void absentValuesTakeTheSameDefaults() {
        assertThat(bind(Map.of())).isEqualTo(bind(Map.of("enabled", "", "display-name", "")));
    }

    @Test
    void disabledNeedsNothing() {
        bind(Map.of("enabled", "false")).requireComplete();
    }

    @Test
    void enabledWithoutIssuerOrClientIdStopsStartupNamingBoth() {
        OidcProperties properties = bind(Map.of("enabled", "true", "issuer-uri", "", "client-id", ""));

        assertThatThrownBy(properties::requireComplete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREFIX + ".issuer-uri")
                .hasMessageContaining(PREFIX + ".client-id")
                .hasMessageContaining(PREFIX + ".enabled=true");
    }

    @Test
    void enabledWithoutOnlyTheClientIdNamesIt() {
        OidcProperties properties = bind(Map.of("enabled", "true", "issuer-uri", "https://idp.example/realm"));

        assertThatThrownBy(properties::requireComplete)
                .hasMessageContaining(PREFIX + ".client-id")
                .hasMessageNotContaining(PREFIX + ".issuer-uri");
    }

    @Test
    void scopesWithoutOpenidAreRefused() {
        OidcProperties properties = bind(Map.of("enabled", "true", "issuer-uri", "https://idp.example/realm",
                "client-id", "pos", "scopes", "profile,email"));

        assertThatThrownBy(properties::requireComplete).hasMessageContaining(PREFIX + ".scopes")
                .hasMessageContaining("openid");
    }

    @Test
    void listedScopesAreKept() {
        assertThat(bind(Map.of("scopes", "openid, profile")).scopes()).containsExactly("openid", "profile");
    }

    @Test
    void theClientSecretIsReadFromItsFileStripped() throws Exception {
        Path file = Files.writeString(dir.resolve("secret"), "  s3cr3t-value\n");

        assertThat(bind(Map.of("client-secret-file", file.toString())).clientSecret()).isEqualTo("s3cr3t-value");
    }

    @Test
    void noFileOrAnEmptyFileMeansAPublicClient() throws Exception {
        Path empty = Files.writeString(dir.resolve("empty"), "\n");

        assertThat(bind(Map.of()).clientSecret()).isNull();
        assertThat(bind(Map.of("client-secret-file", empty.toString())).clientSecret()).isNull();
    }

    @Test
    void anUnreadableSecretFileStopsStartupWithoutItsContent() {
        Path missing = dir.resolve("missing");
        OidcProperties properties = bind(Map.of("enabled", "true", "issuer-uri", "https://idp.example/realm",
                "client-id", "pos", "client-secret-file", missing.toString()));

        assertThatThrownBy(properties::requireComplete).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREFIX + ".client-secret-file")
                .hasMessageContaining(missing.toString());
    }

    @Test
    void aCompleteConfigurationPasses() {
        bind(Map.of("enabled", "true", "issuer-uri", "https://idp.example/realm", "client-id", "pos"))
                .requireComplete();
    }

    private static OidcProperties bind(Map<String, String> values) {
        Map<String, String> prefixed = new java.util.HashMap<>();
        values.forEach((key, value) -> prefixed.put(PREFIX + "." + key, value));
        return new Binder(new MapConfigurationPropertySource(prefixed)).bindOrCreate(PREFIX, OidcProperties.class);
    }
}
