package com.xavier.backend.identity.oidc;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local OpenID Provider for tests (no network beyond loopback): discovery, JWKS and a token endpoint that redeems
 * codes the test registered with {@link #issue}, signing RS256 ID tokens with a key generated per stub. It checks
 * what a real provider checks for this flow: the client's basic credentials, the PKCE verifier against the
 * challenge, and that a code is redeemed once. Every request path is recorded, so a test can prove the backend made
 * no call.
 */
public final class StubIdp implements AutoCloseable {

    public static final String CLIENT_ID = "restaurantpro-test";
    /** The content of {@code src/test/resources/identity/oidc-client-secret.txt}: a test value, never a real one. */
    public static final String CLIENT_SECRET = "stub-client-secret-for-tests";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What the IdP says about the user when the code is redeemed. */
    public record Grant(String subject, String nonce, String codeChallenge, List<String> amr,
                        Map<String, Object> extraClaims, String audience) {

        public static Grant of(String subject, String nonce, String codeChallenge) {
            return new Grant(subject, nonce, codeChallenge, List.of("pwd"), Map.of(), CLIENT_ID);
        }

        public Grant withAmr(List<String> methods) {
            return new Grant(subject, nonce, codeChallenge, methods, extraClaims, audience);
        }

        public Grant withClaims(Map<String, Object> claims) {
            return new Grant(subject, nonce, codeChallenge, amr, claims, audience);
        }

        public Grant withNonce(String otherNonce) {
            return new Grant(subject, otherNonce, codeChallenge, amr, extraClaims, audience);
        }
    }

    private final RSAKey key;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Set<String> redeemed = ConcurrentHashMap.newKeySet();
    private volatile HttpServer server;
    private volatile int port;
    /** While set, a discovery request is accepted and never answered (a black hole), until {@link #answer()}. */
    private volatile java.util.concurrent.CountDownLatch hold;
    private volatile String announcedIssuer;
    private volatile java.util.concurrent.CountDownLatch holdToken;

    private StubIdp() {
        try {
            key = new RSAKeyGenerator(2048).keyID("stub-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A running stub on a free loopback port. */
    public static StubIdp start() {
        StubIdp stub = new StubIdp();
        stub.listen(0);
        return stub;
    }

    /** A stub that is not running: its issuer points at a loopback port nobody listens on, until {@link #restart}. */
    public static StubIdp stopped() {
        StubIdp stub = start();
        stub.stop();
        return stub;
    }

    public String issuer() {
        return "http://127.0.0.1:" + port + "/realm";
    }

    public void issue(String code, Grant grant) {
        grants.put(code, grant);
    }

    public List<String> requests() {
        return Collections.unmodifiableList(requests);
    }

    public boolean redeemed(String code) {
        return redeemed.contains(code);
    }

    /** From now on, discovery requests are accepted and never answered. */
    public void blackHole() {
        hold = new java.util.concurrent.CountDownLatch(1);
    }

    /** From now on, token requests are accepted and never answered, until {@link #answer()}. */
    public void blackHoleToken() {
        holdToken = new java.util.concurrent.CountDownLatch(1);
    }

    /** Held requests get their answer (by now the client has given up), and later ones are answered at once. */
    public void answer() {
        java.util.concurrent.CountDownLatch held = hold;
        java.util.concurrent.CountDownLatch heldToken = holdToken;
        hold = null;
        holdToken = null;
        if (held != null) {
            held.countDown();
        }
        if (heldToken != null) {
            heldToken.countDown();
        }
    }

    /** The discovery document names this issuer instead of the stub's own. */
    public void announceIssuer(String issuer) {
        announcedIssuer = issuer;
    }

    public void stop() {
        answer();
        server.stop(0);
    }

    /** Listens again on the same port (the backend's configured issuer). */
    public void restart() {
        listen(port);
    }

    @Override
    public void close() {
        stop();
    }

    private void listen(int onPort) {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), onPort), 0);
            http.createContext("/", this::handle);
            http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); // held requests do not block others
            http.start();
            server = http;
            port = http.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(path);
        try (exchange) {
            switch (path) {
                case "/realm/.well-known/openid-configuration" -> {
                    java.util.concurrent.CountDownLatch held = hold;
                    if (held != null) {
                        held.await();
                    }
                    json(exchange, 200, discovery());
                }
                case "/realm/jwks" -> json(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject());
                case "/realm/token" -> {
                    java.util.concurrent.CountDownLatch held = holdToken;
                    if (held != null) {
                        held.await();
                    }
                    token(exchange);
                }
                default -> json(exchange, 404, Map.of("error", "not_found"));
            }
        } catch (Exception e) {
            json(exchange, 500, Map.of("error", "server_error"));
        }
    }

    private Map<String, Object> discovery() {
        String issuer = issuer();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("issuer", announcedIssuer != null ? announcedIssuer : issuer);
        document.put("authorization_endpoint", issuer + "/authorize");
        document.put("token_endpoint", issuer + "/token");
        document.put("jwks_uri", issuer + "/jwks");
        document.put("response_types_supported", List.of("code"));
        document.put("subject_types_supported", List.of("public"));
        document.put("id_token_signing_alg_values_supported", List.of("RS256"));
        document.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
        document.put("code_challenge_methods_supported", List.of("S256"));
        return document;
    }

    private void token(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        if (!expectedAuth.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            json(exchange, 401, Map.of("error", "invalid_client"));
            return;
        }
        String code = form.get("code");
        Grant grant = code == null ? null : grants.remove(code);
        if (!"authorization_code".equals(form.get("grant_type")) || grant == null
                || !grant.codeChallenge().equals(s256(form.get("code_verifier")))) {
            json(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        redeemed.add(code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", "stub-access-" + code);
        body.put("token_type", "Bearer");
        body.put("expires_in", 300);
        body.put("scope", "openid");
        body.put("id_token", idToken(grant));
        json(exchange, 200, body);
    }

    private String idToken(Grant grant) throws Exception {
        Instant now = Instant.now(); // the backend validates against the real clock too
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer()).subject(grant.subject()).audience(grant.audience())
                .issueTime(java.util.Date.from(now)).expirationTime(java.util.Date.from(now.plusSeconds(300)))
                .claim("nonce", grant.nonce()).claim("amr", grant.amr());
        grant.extraClaims().forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    public static String s256(String verifier) {
        if (verifier == null) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
