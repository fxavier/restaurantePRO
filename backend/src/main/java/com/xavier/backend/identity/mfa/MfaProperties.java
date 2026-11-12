package com.xavier.backend.identity.mfa;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code restaurantpro.security.mfa.*} (PRD R5 frozen names; Compose sets {@code RESTAURANTPRO_MFA_*}). The defaults
 * live here, not in {@code application.yaml}.
 *
 * @param mode         {@code OFF} by default (P52): an upgraded installation behaves as R4 until its operator opts in
 * @param stepUpMaxAge how long a proved second factor stays fresh for a sensitive permission (P53)
 * @param keyFile      a secret file with 32 random bytes in base64, the AES-256-GCM key of the TOTP secrets; required
 *                     unless {@code mode} is {@code OFF}
 */
@ConfigurationProperties("restaurantpro.security.mfa")
public record MfaProperties(@DefaultValue("OFF") MfaMode mode, @DefaultValue("PT15M") Duration stepUpMaxAge,
                            Path keyFile) {
}
