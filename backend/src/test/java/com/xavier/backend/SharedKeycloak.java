package com.xavier.backend;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * One Keycloak for the whole test JVM (D189 as amended), started lazily by {@link #instance()} with the two realms
 * of {@code deploy/keycloak} (the same files the dev Compose imports, D4: tests = production). Stopped by Ryuk when the
 * JVM exits. Only the identity and end-to-end suites use it; every other test authenticates with a test JWT (S72).
 */
public final class SharedKeycloak {

    /** Keycloak 26.x, pinned (D175: minor upgrades are a planned change). */
    public static final String IMAGE = "quay.io/keycloak/keycloak:26.8.0";

    /** Where the pom puts {@code deploy/keycloak/realm-*.json} on the test classpath. */
    static final String CLASSPATH_DIRECTORY = "keycloak/";

    private final KeycloakContainer container;

    private SharedKeycloak(KeycloakContainer container) {
        this.container = container;
    }

    /** The shared server, started on the first call. */
    public static SharedKeycloak instance() {
        return Holder.INSTANCE;
    }

    /** The realms of {@code deploy/keycloak}: every {@code realm-<name>.json} there (S75 adds its devices realm). */
    public static List<String> realmNames() {
        return realmFiles().stream().map(file -> file.substring("realm-".length(), file.length() - ".json".length()))
                .toList();
    }

    /** The issuer of a realm, e.g. {@code restaurantpro} or {@code restaurantpro-ops}. */
    public String issuer(String realm) {
        return container.getAuthServerUrl().replaceAll("/$", "") + "/realms/" + realm;
    }

    public KeycloakContainer container() {
        return container;
    }

    private static final class Holder {

        static final SharedKeycloak INSTANCE = start();

        /**
         * The module's own realm import ({@code --import-realm} from classpath files): a custom command would replace
         * Keycloak's {@code start-dev} and the container would exit with code 2.
         */
        private static SharedKeycloak start() {
            KeycloakContainer container = new KeycloakContainer(IMAGE).withRealmImportFiles(realmFiles().stream()
                    .map(file -> CLASSPATH_DIRECTORY + file).toArray(String[]::new))
                    .withStartupTimeout(Duration.ofMinutes(5)); // a first start builds Keycloak, slow on a busy host
            container.start();
            return new SharedKeycloak(container);
        }
    }

    static List<String> realmFiles() {
        try (Stream<Path> files = Files.list(realmDirectory())) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith("realm-") && name.endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path realmDirectory() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("deploy/keycloak");
            if (Files.isRegularFile(candidate.resolve("realm-restaurantpro.json"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("deploy/keycloak not found above " + Path.of("").toAbsolutePath());
    }
}
