package com.xavier.bff;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientKeyFileTest {

    @TempDir
    Path dir;

    @Test
    void loadsAPrivateJwk() throws Exception {
        RSAKey key = StubAuthorizationServer.generate("k1");
        Path file = Files.writeString(dir.resolve("key.jwk"), key.toJSONString());

        assertThat(ClientKeyFile.load(file).toRSAKey().toRSAPrivateKey()).isEqualTo(key.toRSAPrivateKey());
    }

    @Test
    void refusesAPublicOnlyKeyWithoutEchoingIt() throws Exception {
        RSAKey key = StubAuthorizationServer.generate("k1").toPublicJWK();
        Path file = Files.writeString(dir.resolve("key.jwk"), key.toJSONString());

        assertThatThrownBy(() -> ClientKeyFile.load(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString())
                .hasMessageNotContaining(key.getModulus().toString())
                .hasNoCause();
    }

    @Test
    void refusesAFileThatIsNotAJwkWithoutEchoingItsContent() throws Exception {
        Path file = Files.writeString(dir.resolve("key.pem"), "-----BEGIN PRIVATE KEY-----\nc2VjcmV0LWtleS1tYXRlcmlhbA==\n");

        assertThatThrownBy(() -> ClientKeyFile.load(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("c2VjcmV0")
                .hasNoCause();
    }

    @Test
    void refusesAMissingFile() {
        assertThatThrownBy(() -> ClientKeyFile.load(dir.resolve("absent.jwk")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent.jwk");
    }
}
