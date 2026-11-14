package com.xavier.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The database roles of ADR 0039 (D163) in a test container: {@code deploy/postgres/provision-roles.sql}, the
 * production file, run as the container's superuser with passwords generated per JVM (never secrets), and the
 * connections of the pool ({@value #APP_ROLE}) and of Flyway ({@value #OWNER_ROLE}). Reading it does not start the
 * shared container, so a test with its own database ({@code R1AcceptanceTest}) uses it on its own.
 */
public final class DatabaseRoles {

    public static final String APP_ROLE = "restaurantpro_app";
    public static final String OWNER_ROLE = "restaurantpro_owner";

    private static final String APP_PASSWORD = generatedPassword();
    private static final String OWNER_PASSWORD = generatedPassword();
    private static final String PROVISIONING_IN_CONTAINER = "/tmp/provision-roles.sql";

    private DatabaseRoles() {
    }

    /**
     * Runs {@code deploy/postgres/provision-roles.sql} in {@code container} as its superuser, with this JVM's
     * passwords, as {@code db-provision} does in production. Idempotent.
     */
    public static void provision(PostgreSQLContainer container) {
        Path file = provisioningFile();
        container.copyFileToContainer(MountableFile.forHostPath(file), PROVISIONING_IN_CONTAINER);
        try {
            ExecResult result = container.execInContainer("psql", "-X", "-q", "-U", container.getUsername(), "-d",
                    container.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-v", "owner_password=" + OWNER_PASSWORD,
                    "-v", "app_password=" + APP_PASSWORD, "-f", PROVISIONING_IN_CONTAINER);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("provision-roles.sql failed (" + result.getExitCode() + "): "
                        + result.getStderr());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not run provision-roles.sql", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while provisioning the database roles", e);
        }
    }

    /** The application role's password in every container of this JVM. */
    public static String appPassword() {
        return APP_PASSWORD;
    }

    /** The owner role's password in every container of this JVM. */
    public static String ownerPassword() {
        return OWNER_PASSWORD;
    }

    /** A {@link JdbcConnectionDetails} of the application role on {@code container}. */
    public static JdbcConnectionDetails applicationConnection(PostgreSQLContainer container) {
        return login(container, APP_ROLE, APP_PASSWORD);
    }

    /** A {@link FlywayConnectionDetails} of the owner role on {@code container}. */
    public static FlywayConnectionDetails ownerConnection(PostgreSQLContainer container) {
        JdbcConnectionDetails owner = login(container, OWNER_ROLE, OWNER_PASSWORD);
        return new FlywayConnectionDetails() {
            @Override
            public String getUsername() {
                return owner.getUsername();
            }

            @Override
            public String getPassword() {
                return owner.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return owner.getJdbcUrl();
            }
        };
    }

    private static JdbcConnectionDetails login(PostgreSQLContainer container, String username, String password) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return username;
            }

            @Override
            public String getPassword() {
                return password;
            }

            @Override
            public String getJdbcUrl() {
                return container.getJdbcUrl();
            }
        };
    }

    private static Path provisioningFile() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("deploy/postgres/provision-roles.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("deploy/postgres/provision-roles.sql not found above " + Path.of("")
                .toAbsolutePath());
    }

    private static String generatedPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

}
