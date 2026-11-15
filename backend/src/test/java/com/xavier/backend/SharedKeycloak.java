package com.xavier.backend;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testcontainers.utility.MountableFile;

/**
 * One Keycloak for the whole test JVM (D189 as amended), started lazily by {@link #instance()} with the two realms
 * of {@code deploy/keycloak} (the same files the dev Compose imports, D4: tests = production). Stopped by Ryuk when the
 * JVM exits. Only the identity and end-to-end suites use it; every other test authenticates with a test JWT (S72).
 */
public final class SharedKeycloak {

    /** Keycloak 26.x, pinned (D175: minor upgrades are a planned change). */
    public static final String IMAGE = "quay.io/keycloak/keycloak:26.8.0";

    static final List<String> REALM_FILES = List.of("realm-restaurantpro.json", "realm-restaurantpro-ops.json");

    private final KeycloakContainer container;

    private SharedKeycloak(KeycloakContainer container) {
        this.container = container;
    }

    /** The shared server, started on the first call. */
    public static SharedKeycloak instance() {
        return Holder.INSTANCE;
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

        private static SharedKeycloak start() {
            Path realms = realmDirectory();
            KeycloakContainer container = new KeycloakContainer(IMAGE);
            for (String file : REALM_FILES) {
                container.withCopyFileToContainer(MountableFile.forHostPath(realms.resolve(file)),
                        "/opt/keycloak/data/import/" + file);
            }
            container.withCustomCommand("--import-realm");
            container.start();
            return new SharedKeycloak(container);
        }
    }

    static Path realmDirectory() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("deploy/keycloak");
            if (Files.isRegularFile(candidate.resolve(REALM_FILES.getFirst()))) {
                return candidate;
            }
        }
        throw new IllegalStateException("deploy/keycloak not found above " + Path.of("").toAbsolutePath());
    }
}
