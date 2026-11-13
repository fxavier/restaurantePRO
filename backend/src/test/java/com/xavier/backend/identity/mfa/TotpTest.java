package com.xavier.backend.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RFC 6238 Appendix B, SHA-1 rows, truncated to the 6 digits authenticator apps show (D139): the 8-digit value
 * modulo 10^6. The secret is the RFC's ASCII "12345678901234567890" (a published test key).
 */
class TotpTest {

    static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({
            "59, 287082",
            "1111111109, 081804",
            "1111111111, 050471",
            "1234567890, 005924",
            "2000000000, 279037",
            "20000000000, 353130"})
    void matchesTheRfc6238Sha1Vectors(long epochSeconds, String code) {
        long step = Totp.stepAt(Instant.ofEpochSecond(epochSeconds));

        assertThat(Totp.codeAt(RFC_SECRET, step)).isEqualTo(Integer.parseInt(code));
        assertThat(Totp.verify(RFC_SECRET, Integer.parseInt(code), step)).isTrue();
    }

    @Test
    void aStepIsThirtySecondsFromTheEpoch() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC);

        assertThat(Totp.stepAt(clock.instant())).isEqualTo(1);
        assertThat(Totp.stepAt(Instant.ofEpochSecond(60))).isEqualTo(2);
    }

    @Test
    void aCodeOfAnotherStepDoesNotVerifyAtThisOne() {
        long step = Totp.stepAt(Instant.ofEpochSecond(1111111109));

        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step), step + 1)).isFalse();
    }

    @Test
    void theWindowAcceptsTheNeighbouringStepsAndNoFurther() {
        long now = Totp.stepAt(Instant.ofEpochSecond(1234567890));

        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, now - 1), now, 0)).hasValue(now - 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, now), now, 0)).hasValue(now);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, now + 1), now, 0)).hasValue(now + 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, now - 2), now, 0)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, now + 2), now, 0)).isEmpty();
    }

    @Test
    void aStepAlreadyUsedNeverMatchesAgain() {
        long now = Totp.stepAt(Instant.ofEpochSecond(1234567890));
        int code = Totp.codeAt(RFC_SECRET, now);

        assertThat(Totp.matchingStep(RFC_SECRET, code, now, now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, code, now, now - 1)).hasValue(now);
    }
}
