package com.xavier.backend.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Code formats and how codes are stored and matched (D139, D143). */
class MfaCodesTest {

    @Test
    void aRecoveryCodeIsTenBase32CharactersInTwoGroups() {
        assertThat(MfaCodes.newRecoveryCode()).matches("[A-Z2-7]{5}-[A-Z2-7]{5}");
    }

    @Test
    void anEnrolmentCodeIsTwelveBase32CharactersInThreeGroups() {
        assertThat(MfaCodes.newEnrolmentCode()).matches("[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}");
    }

    @Test
    void aSecretIs160RandomBits() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            byte[] secret = MfaCodes.newSecret();
            assertThat(secret).hasSize(20);
            seen.add(Base32.encode(secret));
        }
        assertThat(seen).hasSize(100);
    }

    @Test
    void theHashIgnoresCaseDashesAndSpaces() {
        String hash = MfaCodes.hash("ABCDE-FGHIJ");

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(MfaCodes.hash("abcdefghij")).isEqualTo(hash);
        assertThat(MfaCodes.hash(" abcde fghij ")).isEqualTo(hash);
        assertThat(MfaCodes.hash("ABCDE-FGHIK")).isNotEqualTo(hash);
    }

    @Test
    void theKeyUriNamesTheIssuerAndTheAccount() {
        assertThat(MfaCodes.otpauthUri("Café Sol & Mar", "ana.silva", "GEZDGNBVGY3TQOJQ"))
                .isEqualTo("otpauth://totp/Caf%C3%A9%20Sol%20%26%20Mar:ana.silva?secret=GEZDGNBVGY3TQOJQ"
                        + "&issuer=Caf%C3%A9%20Sol%20%26%20Mar&algorithm=SHA1&digits=6&period=30");
    }
}
