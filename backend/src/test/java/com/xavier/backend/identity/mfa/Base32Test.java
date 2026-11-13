package com.xavier.backend.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** RFC 4648 §10 test vectors for base32 (the alphabet authenticator apps read a TOTP secret in). */
class Base32Test {

    @ParameterizedTest
    @CsvSource(value = {
            "'', ''",
            "f, MY======",
            "fo, MZXQ====",
            "foo, MZXW6===",
            "foob, MZXW6YQ=",
            "fooba, MZXW6YTB",
            "foobar, MZXW6YTBOI======"})
    void encodesTheRfc4648Vectors(String plain, String encoded) {
        assertThat(Base32.encode(plain.getBytes(StandardCharsets.US_ASCII))).isEqualTo(encoded);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "'', ''",
            "MY======, f",
            "MZXQ====, fo",
            "MZXW6===, foo",
            "MZXW6YQ=, foob",
            "MZXW6YTB, fooba",
            "MZXW6YTBOI======, foobar"})
    void decodesTheRfc4648Vectors(String encoded, String plain) {
        assertThat(new String(Base32.decode(encoded), StandardCharsets.US_ASCII)).isEqualTo(plain);
    }

    @Test
    void decodingIgnoresCaseAndMissingPadding() {
        assertThat(new String(Base32.decode("mzxw6ytboi"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
    }

    @Test
    void aTwentyByteSecretNeedsNoPadding() {
        assertThat(Base32.encode(new byte[20])).hasSize(32).doesNotContain("=");
    }

    @Test
    void refusesALetterOutsideTheAlphabet() {
        assertThatThrownBy(() -> Base32.decode("MZXW1YTB")).isInstanceOf(IllegalArgumentException.class);
    }
}
