package com.xavier.bridge;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * The print bridge's entry point (D187), run as a service on an always-on device of the store's LAN
 * ({@code packaging/}). {@code enrol <code>} enrols it once; with no argument it runs. Configuration, from the
 * environment: {@code RESTAURANTPRO_BRIDGE_BACKEND_URL} (the API), {@code RESTAURANTPRO_BRIDGE_ISSUER} (the
 * {@code restaurantpro-devices} realm), {@code RESTAURANTPRO_BRIDGE_CLIENT_ID} (answered by the enrolment) and
 * {@code RESTAURANTPRO_BRIDGE_KEY_FILE} (the private key's file); optional {@code RESTAURANTPRO_BRIDGE_POLL_INTERVAL}
 * (default 2 s).
 */
public final class BridgeMain {

    private static final Duration DEFAULT_POLL = Duration.ofSeconds(2);

    private BridgeMain() {
    }

    public static void main(String[] args) throws Exception {
        URI backend = URI.create(required("RESTAURANTPRO_BRIDGE_BACKEND_URL"));
        Path keyFile = Path.of(required("RESTAURANTPRO_BRIDGE_KEY_FILE"));
        if (args.length == 2 && "enrol".equals(args[0])) {
            System.out.println("Enrolled: RESTAURANTPRO_BRIDGE_CLIENT_ID=" + BridgeEnrolment.enrol(backend, args[1],
                    keyFile));
            return;
        }
        AccessTokens tokens = new AccessTokens(URI.create(required("RESTAURANTPRO_BRIDGE_ISSUER")),
                required("RESTAURANTPRO_BRIDGE_CLIENT_ID"), DeviceKeys.load(keyFile), Clock.systemUTC());
        try (PrinterSender printers = new PrinterSender(Duration.ofSeconds(2), Duration.ofSeconds(5))) {
            new BridgeAgent(new BackendClient(backend, tokens), printers, pollInterval())
                    .run(new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(60)));
        }
    }

    /** {@code RESTAURANTPRO_BRIDGE_POLL_INTERVAL} (ISO-8601, e.g. {@code PT2S}): how often an idle bridge asks. */
    private static Duration pollInterval() {
        String value = System.getenv("RESTAURANTPRO_BRIDGE_POLL_INTERVAL");
        return value == null || value.isBlank() ? DEFAULT_POLL : Duration.parse(value);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
