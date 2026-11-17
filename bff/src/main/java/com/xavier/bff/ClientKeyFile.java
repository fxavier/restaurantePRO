package com.xavier.bff;

import com.nimbusds.jose.jwk.JWK;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;

/**
 * Reads the backoffice-bff client key. Failures name the file only: a parser's message or cause
 * could quote key material, so neither is kept (security rule 10).
 */
final class ClientKeyFile {

    private ClientKeyFile() {
    }

    static JWK load(Path file) {
        JWK key;
        try {
            key = JWK.parse(Files.readString(file));
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("The BFF client key file cannot be read as a JWK: " + file);
        }
        if (!key.isPrivate()) {
            throw new IllegalStateException("The BFF client key file holds no private key: " + file);
        }
        return key;
    }
}
