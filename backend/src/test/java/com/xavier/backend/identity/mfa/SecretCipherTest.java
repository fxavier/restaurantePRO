package com.xavier.backend.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** AES-256-GCM sealing of TOTP secrets (D139) and the fail-fast start when MFA is on without a key. */
class SecretCipherTest {

    static final UUID ACCOUNT = UUID.fromString("0190b000-0000-7000-8000-000000000001");
    /** A fixed test key: bytes 1..32. Never a real key. */
    static final byte[] TEST_KEY = testKey();

    @TempDir
    Path dir;

    @Test
    void aSealedSecretOpensBackToTheSameBytes() throws IOException {
        SecretCipher cipher = new SecretCipher(properties(MfaMode.SENSITIVE, keyFile(TEST_KEY)));
        byte[] secret = "12345678901234567890".getBytes();

        SecretCipher.Sealed sealed = cipher.seal(secret, ACCOUNT);

        assertThat(sealed.ciphertext()).isNotEqualTo(secret);
        assertThat(sealed.nonce()).hasSize(12);
        assertThat(sealed.keyId()).hasSizeBetween(1, 20);
        assertThat(cipher.open(sealed, ACCOUNT)).isEqualTo(secret);
    }

    @Test
    void everySealingUsesAFreshNonce() throws IOException {
        SecretCipher cipher = new SecretCipher(properties(MfaMode.SENSITIVE, keyFile(TEST_KEY)));
        byte[] secret = new byte[20];

        assertThat(cipher.seal(secret, ACCOUNT).nonce()).isNotEqualTo(cipher.seal(secret, ACCOUNT).nonce());
    }

    @Test
    void aTamperedCiphertextDoesNotOpen() throws IOException {
        SecretCipher cipher = new SecretCipher(properties(MfaMode.SENSITIVE, keyFile(TEST_KEY)));
        SecretCipher.Sealed sealed = cipher.seal(new byte[20], ACCOUNT);
        byte[] tampered = sealed.ciphertext().clone();
        tampered[0] ^= 1;

        assertThatThrownBy(() -> cipher.open(new SecretCipher.Sealed(tampered, sealed.nonce(), sealed.keyId()),
                ACCOUNT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSecretSealedForOneAccountDoesNotOpenForAnother() throws IOException {
        SecretCipher cipher = new SecretCipher(properties(MfaMode.SENSITIVE, keyFile(TEST_KEY)));
        SecretCipher.Sealed sealed = cipher.seal(new byte[20], ACCOUNT);

        assertThatThrownBy(() -> cipher.open(sealed, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKeyDoesNotOpenIt() throws IOException {
        SecretCipher.Sealed sealed = new SecretCipher(properties(MfaMode.SENSITIVE, keyFile(TEST_KEY)))
                .seal(new byte[20], ACCOUNT);
        byte[] otherKey = TEST_KEY.clone();
        otherKey[31] ^= 1;
        SecretCipher other = new SecretCipher(properties(MfaMode.SENSITIVE, keyFile(otherKey)));

        assertThat(other.keyId()).isNotEqualTo(sealed.keyId());
        assertThatThrownBy(() -> other.open(sealed, ACCOUNT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mfaOnWithoutAKeyFileDoesNotStart() {
        assertThatThrownBy(() -> new SecretCipher(properties(MfaMode.SENSITIVE, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("restaurantpro.security.mfa.key-file");
        assertThatThrownBy(() -> new SecretCipher(properties(MfaMode.ALL_BACKOFFICE, dir.resolve("absent"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyThatIsNotThirtyTwoBytesDoesNotStart() throws IOException {
        Path shortKey = keyFile(Arrays.copyOf(TEST_KEY, 16));
        Path notBase64 = Files.writeString(dir.resolve("bad.key"), "not base64 at all!");

        assertThatThrownBy(() -> new SecretCipher(properties(MfaMode.SENSITIVE, shortKey)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new SecretCipher(properties(MfaMode.OFF, notBase64)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mfaOffStartsWithoutAKeyAndCannotSeal() {
        SecretCipher cipher = new SecretCipher(properties(MfaMode.OFF, null));

        assertThat(cipher.configured()).isFalse();
        assertThatThrownBy(() -> cipher.seal(new byte[20], ACCOUNT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theApplicationDoesNotStartWithMfaOnAndNoKey() {
        new ApplicationContextRunner().withUserConfiguration(CipherOnly.class)
                .withPropertyValues("restaurantpro.security.mfa.mode=SENSITIVE")
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner().withUserConfiguration(CipherOnly.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void thePropertiesDefaultToOffAndFifteenMinutes() {
        new ApplicationContextRunner().withUserConfiguration(CipherOnly.class)
                .run(context -> {
                    MfaProperties properties = context.getBean(MfaProperties.class);
                    assertThat(properties.mode()).isEqualTo(MfaMode.OFF);
                    assertThat(properties.stepUpMaxAge()).isEqualTo(Duration.ofMinutes(15));
                    assertThat(properties.keyFile()).isNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MfaProperties.class)
    @Import(SecretCipher.class)
    static class CipherOnly {
    }

    private Path keyFile(byte[] key) throws IOException {
        return Files.writeString(dir.resolve(UUID.randomUUID() + ".key"),
                Base64.getEncoder().encodeToString(key) + "\n");
    }

    private static MfaProperties properties(MfaMode mode, Path keyFile) {
        return new MfaProperties(mode, Duration.ofMinutes(15), keyFile);
    }

    private static byte[] testKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }
}
